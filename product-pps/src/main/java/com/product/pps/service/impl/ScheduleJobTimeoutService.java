package com.product.pps.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cache.constants.RedisLockKeys;
import com.product.cache.lock.RedisDistributedLock;
import com.product.common.constant.StatusConstants;
import com.product.domain.entity.ScheduleJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 排程任务超时兜底清理
 *
 * 职责：
 * - 扫描长时间卡在 PENDING/RUNNING 的 schedule_job
 * - 将超时任务兜底标记为 FAILED
 * - 防止脏状态长期占用系统，导致后续排程无法提交
 *
 * 设计说明：
 * - 使用 Redis 分布式锁避免多实例重复清理
 * - 仅处理已超时任务，不干扰正常执行中的 job
 * - 后台执行线程若晚于兜底清理结束，后续状态流转会受前置状态约束
 */
@Slf4j
@Component
public class ScheduleJobTimeoutService {
    @Value("${product.pps.schedule.timeout-minutes:60}")
    private long timeoutMinutes;

    @Autowired
    private RedisDistributedLock redisDistributedLock;

    /**
     * 定时扫描超时排程任务。
     *
     * 默认每 5 分钟执行一次，扫描阈值由 timeoutMinutes 控制。
     */
    @Scheduled(fixedDelayString = "${product.pps.schedule.timeout-scan-delay-ms:300000}")
    public void sweepTimeoutJobs() {
        sweepTimeoutJobsOnce();
    }

    /**
     * 手动触发一次超时扫描。
     *
     * @return 本次标记失败的任务数
     */
    public int sweepTimeoutJobsOnce() {
        // 分布式锁防止重复扫描
        boolean locked = redisDistributedLock.tryLock(RedisLockKeys.Pps.Schedule.TIMEOUT_SWEEP_LOCK_KEY, 0, 120, TimeUnit.SECONDS);
        if (!locked) {
            return 0;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime timeoutCutoff = now.minusMinutes(timeoutMinutes);
            int pendingFailed = markPendingJobsTimeout(now, timeoutCutoff);
            int runningFailed = markRunningJobsTimeout(now, timeoutCutoff);

            if (pendingFailed > 0 || runningFailed > 0) {
                log.warn("schedule job timeout sweep completed, timeoutMinutes={}, pendingFailed={}, runningFailed={}",
                        timeoutMinutes, pendingFailed, runningFailed);
            } else {
                log.info("schedule job timeout sweep completed, timeoutMinutes={}, no timeout jobs found", timeoutMinutes);
            }
            return pendingFailed + runningFailed;
        } catch (Exception ex) {
            log.error("schedule job timeout sweep failed", ex);
            return 0;
        } finally {
            redisDistributedLock.unlock(RedisLockKeys.Pps.Schedule.TIMEOUT_SWEEP_LOCK_KEY);
        }
    }

    private int markPendingJobsTimeout(LocalDateTime now, LocalDateTime timeoutCutoff) {
        List<Long> jobIds = Db.lambdaQuery(ScheduleJob.class)
                .eq(ScheduleJob::getStatus, StatusConstants.PENDING_SCHEDULE_JOB)
                .lt(ScheduleJob::getCreateTime, timeoutCutoff)
                .list()
                .stream()
                .map(ScheduleJob::getJobId)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(jobIds)) {
            return 0;
        }
        return markJobsFailed(jobIds, StatusConstants.PENDING_SCHEDULE_JOB, now,
                "排程任务创建后长时间未执行，已自动超时失败");
    }

    private int markRunningJobsTimeout(LocalDateTime now, LocalDateTime timeoutCutoff) {
        List<Long> jobIds = Db.lambdaQuery(ScheduleJob.class)
                .eq(ScheduleJob::getStatus, StatusConstants.RUNNING_SCHEDULE_JOB)
                .lt(ScheduleJob::getStartedAt, timeoutCutoff)
                .list()
                .stream()
                .map(ScheduleJob::getJobId)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(jobIds)) {
            return 0;
        }
        return markJobsFailed(jobIds, StatusConstants.RUNNING_SCHEDULE_JOB, now,
                "排程任务执行超时，已自动标记失败");
    }

    private int markJobsFailed(List<Long> jobIds, String expectedStatus, LocalDateTime now, String reason) {
        if (CollectionUtils.isEmpty(jobIds)) {
            return 0;
        }
        String safeReason = reason == null ? "排程任务超时" : reason;
        if (safeReason.length() > 500) {
            safeReason = safeReason.substring(0, 500);
        }
        Long targetCount = Db.lambdaQuery(ScheduleJob.class)
                .in(ScheduleJob::getJobId, jobIds)
                .eq(ScheduleJob::getStatus, expectedStatus)
                .count();
        boolean updated = Db.lambdaUpdate(ScheduleJob.class)
                .set(ScheduleJob::getStatus, StatusConstants.FAILED_SCHEDULE_JOB)
                .set(ScheduleJob::getFinishedAt, now)
                .set(ScheduleJob::getErrorMessage, safeReason)
                .in(ScheduleJob::getJobId, jobIds)
                .eq(ScheduleJob::getStatus, expectedStatus)
                .update();
        int updatedCount = updated ? (targetCount == null ? 0 : targetCount.intValue()) : 0;
        if (updatedCount > 0) {
            log.warn("schedule job timeout sweep marked failed, expectedStatus={}, jobIds={}", expectedStatus, jobIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        }
        return updatedCount;
    }
}
