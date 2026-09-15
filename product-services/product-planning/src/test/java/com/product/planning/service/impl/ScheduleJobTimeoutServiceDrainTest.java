package com.product.planning.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.planning.config.PlanningLockKeys;
import com.product.planning.config.PlanningRedisLock;
import com.product.planning.domain.entity.ScheduleJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时清扫宿主的排空步骤测试（KD2，2026-09-16）：
 * 既有 @Scheduled 节拍内追加「排空重排 pending 标记」一步——定时入口同时做超时清扫与
 * 排空；手动清扫入口（/scheduleJob/timeout-sweep 语义）保持只做超时清扫、不排空；
 * 排空异常不影响超时清扫主职责。既有行为（锁互斥、超时标记）保持零变化。
 */
class ScheduleJobTimeoutServiceDrainTest {

    private PlanningRedisLock redisLock;
    private RescheduleTriggerService rescheduleTriggerService;
    private ScheduleJobTimeoutService timeoutService;

    @BeforeEach
    void setUp() {
        redisLock = mock(PlanningRedisLock.class);
        rescheduleTriggerService = mock(RescheduleTriggerService.class);
        timeoutService = new ScheduleJobTimeoutService(60, redisLock, rescheduleTriggerService);
    }

    /** 离线桩：超时清扫的 Db 工具链返回空（无超时任务），锁可获取。 */
    private MockedStatic<Db> stubSweepQueries() {
        MockedStatic<Db> db = mockStatic(Db.class);
        LambdaQueryChainWrapper<ScheduleJob> wrapper = mock(LambdaQueryChainWrapper.class,
                Mockito.withSettings().defaultAnswer(invocation -> {
                    Class<?> returnType = invocation.getMethod().getReturnType();
                    Object self = invocation.getMock();
                    return returnType == Object.class || returnType.isInstance(self) ? self : null;
                }));
        doReturn(List.of()).when(wrapper).list();
        db.when(() -> Db.lambdaQuery(ScheduleJob.class)).thenReturn(wrapper);
        when(redisLock.tryLock(eq(PlanningLockKeys.Schedule.TIMEOUT_SWEEP_LOCK_KEY), eq(0L),
                eq(120L), eq(TimeUnit.SECONDS))).thenReturn(true);
        return db;
    }

    @Test
    void scheduledSweepShouldAlsoDrainPendingMarker() {
        when(redisLock.tryLock(eq(PlanningLockKeys.Schedule.TIMEOUT_SWEEP_LOCK_KEY), anyLong(),
                anyLong(), any(TimeUnit.class))).thenReturn(true);
        try (MockedStatic<Db> db = stubSweepQueries()) {
            timeoutService.sweepTimeoutJobs();

            // 既有清扫节拍内追加排空一步（KD2：标记存在且空闲时补跑）
            verify(rescheduleTriggerService).drainPendingIfIdle();
        }
    }

    @Test
    void manualSweepEntryShouldNotDrain() {
        when(redisLock.tryLock(eq(PlanningLockKeys.Schedule.TIMEOUT_SWEEP_LOCK_KEY), anyLong(),
                anyLong(), any(TimeUnit.class))).thenReturn(true);
        try (MockedStatic<Db> db = stubSweepQueries()) {
            int failed = timeoutService.sweepTimeoutJobsOnce();

            // 手动清扫入口保持原语义：只做超时标记，不排空重排标记
            org.junit.jupiter.api.Assertions.assertEquals(0, failed);
            verify(rescheduleTriggerService, never()).drainPendingIfIdle();
        }
    }

    @Test
    void drainFailureShouldNotBreakTimeoutSweep() {
        when(redisLock.tryLock(eq(PlanningLockKeys.Schedule.TIMEOUT_SWEEP_LOCK_KEY), anyLong(),
                anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new IllegalStateException("drain blew up"))
                .when(rescheduleTriggerService).drainPendingIfIdle();
        try (MockedStatic<Db> db = stubSweepQueries()) {
            // 排空异常仅记录，不影响清扫主职责（不向上传播）
            assertDoesNotThrow(() -> timeoutService.sweepTimeoutJobs());
            verify(rescheduleTriggerService).drainPendingIfIdle();
        }
    }

    @Test
    void lockUnavailableShouldSkipSweepQuietlyAndStillDrain() {
        // 锁不可得时清扫静默跳过（既有语义）；排空不依赖清扫锁，仍执行
        when(redisLock.tryLock(eq(PlanningLockKeys.Schedule.TIMEOUT_SWEEP_LOCK_KEY), anyLong(),
                anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertDoesNotThrow(() -> timeoutService.sweepTimeoutJobs());

        verify(rescheduleTriggerService).drainPendingIfIdle();
        Mockito.verify(redisLock, Mockito.never()).unlock(any(String.class));
    }
}
