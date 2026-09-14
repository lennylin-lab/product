package com.product.planning.config;

import com.product.planning.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * planning 域 Redis 分布式锁（Phase 4）。
 *
 * <p>单体使用 Redisson（RLock + watchdog 自动续期）；Redisson 不在 Boot/SC/SCA 任一 BOM
 * 管理内（ADR-0001"新依赖必须来自 BOM"约束），故按同一语义用 StringRedisTemplate 实现：
 * SET NX PX + 随机持有者令牌 + Lua 释放（只删自己的锁）+ 阻塞式 lock 的看门狗续期
 * （持有期间按租期/3 周期续租，等价 Redisson watchdog）。</p>
 *
 * <p>三个使用点与单体一一对应（key 见 {@link PlanningLockKeys}）：
 * 异步排程提交互斥（tryLock 立即失败）、排程执行互斥（lock 阻塞 + 看门狗）、
 * 超时扫描互斥（tryLock 0 等待 + 120s 租期）。Redis 不可用时排程互斥不可用 →
 * 抛 ServiceException（排程任务标记 FAILED，不静默降级为无锁执行）。</p>
 */
@Slf4j
@Component
public class PlanningRedisLock {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** 阻塞 lock() 的默认租期（看门狗续期下等价于持有至 unlock/进程退出）。 */
    private static final long LOCK_LEASE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService renewScheduler;
    /** lockKey -> 持有中的锁句柄（本进程视角；unlock/看门狗用）。 */
    private final Map<String, LockHandle> heldLocks = new ConcurrentHashMap<>();

    public PlanningRedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "planning-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        this.renewScheduler = scheduler;
    }

    private static final class LockHandle {
        final String key;
        final String token;
        final long leaseMillis;
        final boolean renew;
        volatile ScheduledFuture<?> watchdog;

        LockHandle(String key, String token, long leaseMillis, boolean renew) {
            this.key = key;
            this.token = token;
            this.leaseMillis = leaseMillis;
            this.renew = renew;
        }
    }

    /**
     * 阻塞式加锁 + 看门狗自动续期（单体 lock() 语义）：租期 30s、每 10s 续期，
     * 与持有线程/unlock 生命周期绑定；进程被杀后锁在租期内自动过期。
     */
    public void lock(String lockKey) {
        long deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        while (true) {
            LockHandle handle = tryAcquire(lockKey, LOCK_LEASE_MILLIS, true);
            if (handle != null) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ServiceException("获取排程分布式锁超时: " + lockKey);
            }
            sleepQuietly();
        }
    }

    /**
     * 尝试加锁（单体 tryLock(waitTime, leaseTime, unit) 语义）：
     * waitTime 内未获得即返回 false；获得后由看门狗按 leaseTime 续期（与单体 tryLock 固定
     * 租期不同——单体 tryLock 无看门狗、租期到即释放，此处保持固定租期语义不续期时，
     * 调用方临界区必须小于租期；submit(30s)/sweep(120s) 均满足，故看门狗仅用于 lock()）。
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        long deadline = System.currentTimeMillis() + unit.toMillis(waitTime);
        long leaseMillis = unit.toMillis(leaseTime);
        while (true) {
            LockHandle handle = tryAcquire(lockKey, leaseMillis, false);
            if (handle != null) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            sleepQuietly();
        }
    }

    /** 释放锁（只删自己持有的令牌；同时停看门狗）。 */
    public void unlock(String lockKey) {
        LockHandle handle = heldLocks.remove(lockKey);
        if (handle == null) {
            return;
        }
        if (handle.watchdog != null) {
            handle.watchdog.cancel(false);
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), handle.token);
        } catch (Exception e) {
            log.warn("release planning lock failed (will expire by lease): key={}", lockKey, e);
        }
    }

    private LockHandle tryAcquire(String lockKey, long leaseMillis, boolean withWatchdog) {
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(leaseMillis));
        } catch (Exception e) {
            log.error("planning lock acquire failed (redis unavailable): key={}", lockKey, e);
            throw new ServiceException("排程分布式锁不可用（Redis 异常），请稍后重试");
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return null;
        }
        LockHandle handle = new LockHandle(lockKey, token, leaseMillis, withWatchdog);
        heldLocks.put(lockKey, handle);
        if (withWatchdog) {
            startWatchdog(handle);
        }
        return handle;
    }

    private void startWatchdog(LockHandle handle) {
        long renewPeriod = Math.max(1000L, handle.leaseMillis / 3);
        handle.watchdog = renewScheduler.scheduleAtFixedRate(() -> {
            if (heldLocks.get(handle.key) != handle) {
                return;
            }
            try {
                Long renewed = redisTemplate.execute(RENEW_SCRIPT, List.of(handle.key), handle.token,
                        String.valueOf(handle.leaseMillis));
                if (renewed == null || renewed == 0L) {
                    log.warn("planning lock lost during watchdog renew: key={}", handle.key);
                    heldLocks.remove(handle.key, handle);
                }
            } catch (Exception e) {
                log.warn("planning lock watchdog renew failed: key={}", handle.key, e);
            }
        }, renewPeriod, renewPeriod, TimeUnit.MILLISECONDS);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("获取排程分布式锁被中断");
        }
    }
}
