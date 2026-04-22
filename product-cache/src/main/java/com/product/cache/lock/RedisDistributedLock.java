package com.product.cache.lock;

import com.product.common.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁工具
 *
 * 目标：
 * - 将分布式锁能力从具体业务中抽离出来，统一复用
 * - 当前主要用于排程防重和热点缓存保护
 * - 后续如果有资源互斥、任务串行等场景，可直接复用
 */
@Component
public class RedisDistributedLock {
    private final RedissonClient redissonClient;

    public RedisDistributedLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 只获取锁，lock()才是加锁，lock后看门狗自动续期
     * 
     * @param lockKey
     * @return
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 可配置(waitTime),支持超时放弃
     * 
     * @param lockKey
     * @param waitTime
     * @param leaseTime
     * @param unit
     * @return
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        try {
            return getLock(lockKey).tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("获取分布式锁被中断");
        }
    }

    public void lock(String lockKey) {
        getLock(lockKey).lock();
    }

    public void unlock(String lockKey) {
        unlock(getLock(lockKey));
    }

    public void unlock(RLock lock) {
        // 确认锁是当前线程持有的才释放
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
