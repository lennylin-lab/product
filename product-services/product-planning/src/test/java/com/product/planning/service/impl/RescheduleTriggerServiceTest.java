package com.product.planning.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.dto.TaskAssignmentDTO;
import com.product.planning.domain.entity.ScheduleJob;
import com.product.planning.service.IScheduleJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异常驱动重排触发单测（KD2，2026-09-16）：
 * 标记语义（置标 → 提交 → 清标的顺序与异常分级：互斥拒绝/意外异常吞掉且标记保留、
 * 置标失败仍尽力提交）与 sweeper 排空（无标记不动作、有运行任务保留、空闲补跑成功清标、
 * 互斥拒绝保留）。离线单测：Redis/排程服务/Db 工具链全部 mock。
 */
class RescheduleTriggerServiceTest {

    private static final String MARKER_KEY = RescheduleTriggerService.PENDING_MARKER_KEY;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private IScheduleJobService scheduleJobService;
    private RescheduleTriggerService triggerService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        scheduleJobService = mock(IScheduleJobService.class);
        triggerService = new RescheduleTriggerService(redisTemplate, scheduleJobService);
    }

    /** 链式条件方法泛型擦除的统一自返回 Answer（同消费编排测试套路）。 */
    private static org.mockito.stubbing.Answer<Object> selfAnswer() {
        return invocation -> {
            Class<?> returnType = invocation.getMethod().getReturnType();
            Object self = invocation.getMock();
            return returnType == Object.class || returnType.isInstance(self) ? self : null;
        };
    }

    private LambdaQueryChainWrapper<ScheduleJob> stubJobQuery() {
        LambdaQueryChainWrapper<ScheduleJob> wrapper = mock(LambdaQueryChainWrapper.class,
                Mockito.withSettings().defaultAnswer(selfAnswer()));
        return wrapper;
    }

    // ---- requestReschedule：置标 → 提交 → 清标，异常分级 ----

    @Test
    void requestRescheduleShouldMarkThenSubmitThenClear() {
        when(scheduleJobService.scheduleAllAsync(any())).thenReturn(8001L);

        triggerService.requestReschedule();

        // 顺序契约：先置 pending 标记（value=置标时间戳、TTL 30min），再提交全量重排
        InOrder inOrder = inOrder(valueOperations, scheduleJobService);
        inOrder.verify(valueOperations).set(eq(MARKER_KEY), anyString(), eq(Duration.ofMinutes(30)));
        inOrder.verify(scheduleJobService).scheduleAllAsync(any(TaskAssignmentDTO.class));
        // 提交成功 → 比较清除标记
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void requestRescheduleShouldUseManualEntryDefaultSemantics() {
        when(scheduleJobService.scheduleAllAsync(any())).thenReturn(8002L);

        triggerService.requestReschedule();

        // 与人工入口（POST /scheduleAllAsync 空 body）同语义：全字段为空 = 排所有 READY 任务
        org.mockito.ArgumentCaptor<TaskAssignmentDTO> captor =
                org.mockito.ArgumentCaptor.forClass(TaskAssignmentDTO.class);
        verify(scheduleJobService).scheduleAllAsync(captor.capture());
        TaskAssignmentDTO dto = captor.getValue();
        assertNull(dto.getTaskId());
        assertNull(dto.getAssignmentStart());
        assertNull(dto.getScheduleStrategy());
    }

    @Test
    void mutexRejectionShouldBeSwallowedAndKeepMarker() {
        when(scheduleJobService.scheduleAllAsync(any()))
                .thenThrow(new ServiceException("当前已有排程任务在执行，请稍后再试"));

        // 预期分支：互斥拒绝吞掉不抛，标记保留（不清除）
        assertDoesNotThrow(() -> triggerService.requestReschedule());

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void unexpectedSubmitFailureShouldBeSwallowedAndKeepMarker() {
        when(scheduleJobService.scheduleAllAsync(any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> triggerService.requestReschedule());

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void markerSetFailureShouldStillAttemptSubmit() {
        Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOperations).set(any(String.class), any(String.class), any(Duration.class));
        when(scheduleJobService.scheduleAllAsync(any())).thenReturn(8003L);

        // 置标失败仅记录错误，仍尽力提交（不向上抛）
        assertDoesNotThrow(() -> triggerService.requestReschedule());

        verify(scheduleJobService).scheduleAllAsync(any(TaskAssignmentDTO.class));
    }

    // ---- drainPendingIfIdle：sweeper 排空语义 ----

    @Test
    void drainShouldDoNothingWhenNoMarker() {
        when(valueOperations.get(MARKER_KEY)).thenReturn(null);

        boolean drained = triggerService.drainPendingIfIdle();

        assertFalse(drained);
        verify(scheduleJobService, never()).scheduleAllAsync(any());
    }

    @Test
    void drainShouldKeepMarkerWhileScheduleJobsActive() {
        when(valueOperations.get(MARKER_KEY)).thenReturn("1700000000000");
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            LambdaQueryChainWrapper<ScheduleJob> wrapper = stubJobQuery();
            doReturn(2L).when(wrapper).count();
            db.when(() -> Db.lambdaQuery(ScheduleJob.class)).thenReturn(wrapper);

            boolean drained = triggerService.drainPendingIfIdle();

            assertFalse(drained);
            verify(scheduleJobService, never()).scheduleAllAsync(any());
        }
    }

    @Test
    void drainShouldSubmitAndClearWhenIdle() {
        when(valueOperations.get(MARKER_KEY)).thenReturn("1700000000000");
        when(scheduleJobService.scheduleAllAsync(any())).thenReturn(8004L);
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            LambdaQueryChainWrapper<ScheduleJob> wrapper = stubJobQuery();
            doReturn(0L).when(wrapper).count();
            db.when(() -> Db.lambdaQuery(ScheduleJob.class)).thenReturn(wrapper);

            boolean drained = triggerService.drainPendingIfIdle();

            assertTrue(drained);
            verify(scheduleJobService).scheduleAllAsync(any(TaskAssignmentDTO.class));
            // 排空清除用的正是读取到的置标时间戳（比较清除，不误清并发新标记）
            verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(MARKER_KEY)), eq("1700000000000"));
        }
    }

    @Test
    void drainShouldKeepMarkerOnMutexRejection() {
        when(valueOperations.get(MARKER_KEY)).thenReturn("1700000000000");
        when(scheduleJobService.scheduleAllAsync(any()))
                .thenThrow(new ServiceException("当前已有排程任务在执行，请稍后再试"));
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            LambdaQueryChainWrapper<ScheduleJob> wrapper = stubJobQuery();
            doReturn(0L).when(wrapper).count();
            db.when(() -> Db.lambdaQuery(ScheduleJob.class)).thenReturn(wrapper);

            boolean drained = triggerService.drainPendingIfIdle();

            assertFalse(drained);
            verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        }
    }

    @Test
    void drainShouldSwallowMarkerReadFailure() {
        when(valueOperations.get(MARKER_KEY)).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> {
            boolean drained = triggerService.drainPendingIfIdle();
            assertFalse(drained);
        });
        verify(scheduleJobService, never()).scheduleAllAsync(any());
    }

    // ---- 触发集合常量契约 ----

    @Test
    void triggerSetShouldContainExactlyDownAndAvailable() {
        assertEquals(java.util.Set.of("DOWN", "AVAILABLE"),
                RescheduleTriggerService.RESCHEDULE_TRIGGER_STATUSES);
        assertEquals("planning:reschedule:pending", RescheduleTriggerService.PENDING_MARKER_KEY);
    }
}
