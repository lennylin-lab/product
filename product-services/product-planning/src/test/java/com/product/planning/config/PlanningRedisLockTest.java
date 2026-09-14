package com.product.planning.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 排程分布式锁单测（Phase 4；单体 Redisson 语义的最小实现验证）：
 * tryLock 立即失败语义（submit 互斥）、获取成功与释放、Redis 异常时显式失败（不静默无锁执行）。
 */
@ExtendWith(MockitoExtension.class)
class PlanningRedisLockTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PlanningRedisLock lock;

    @BeforeEach
    void setUp() {
        lock = new PlanningRedisLock(redisTemplate);
    }

    @Test
    void tryLockShouldFailImmediatelyWhenKeyHeld() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("planning:pps:schedule:submit"), anyString(), any(Duration.class)))
                .thenReturn(false);

        boolean acquired = lock.tryLock(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY, 0, 30, TimeUnit.SECONDS);

        assertFalse(acquired);
    }

    @Test
    void tryLockShouldSucceedWhenKeyFreeAndUnlockReleases() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(java.util.List.class), any(Object[].class))).thenReturn(1L);

        assertTrue(lock.tryLock(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY, 0, 30, TimeUnit.SECONDS));
        lock.unlock(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY);

        // 释放后可重新获取
        when(valueOperations.setIfAbsent(eq(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertTrue(lock.tryLock(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY, 0, 30, TimeUnit.SECONDS));
    }

    @Test
    void tryLockShouldRejectWithServiceExceptionWhenRedisUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertThrows(com.product.planning.common.exception.ServiceException.class,
                () -> lock.tryLock(PlanningLockKeys.Schedule.SUBMIT_LOCK_KEY, 0, 30, TimeUnit.SECONDS));
    }
}
