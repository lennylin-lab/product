package com.product.planning.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.planning.common.constant.StatusConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.dto.TaskAssignmentDTO;
import com.product.planning.domain.entity.ScheduleJob;
import com.product.planning.service.IScheduleJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 异常驱动重排触发（KD2，2026-09-16）：资源状态事件回写成功后按触发集合自动发起全量重排。
 *
 * <p>语义：pending 标记即持久化——置 Redis 标记（{@link #PENDING_MARKER_KEY}，value=置标
 * 时间戳，TTL 30min 防泄漏）→ 立即尝试 {@link IScheduleJobService#scheduleAllAsync}
 * （默认参数，与人工入口同语义）。提交互斥拒绝（「当前已有排程任务在执行」）是预期分支：
 * 吞掉并保留标记，由 {@link ScheduleJobTimeoutService} 既有清扫节拍在无运行任务时排空
 * 补跑（幂等，不丢重排）。触发动作绝不向上抛错——事件回写已成功，不应因触发层问题进入
 * 重试/DLX。</p>
 *
 * <p>触发集合 {@link #RESCHEDULE_TRIGGER_STATUSES} = {DOWN, AVAILABLE}：DOWN 故障排除、
 * AVAILABLE 恢复回归；MAINTENANCE/OFFSHIFT/BUSY 只回写不触发；EXCEPTION 任务事件走
 * task.status.changed 消费路径，与本链路无关（无容量变化）。</p>
 */
@Slf4j
@Service
public class RescheduleTriggerService {

    /** 重排触发集合（KD2/R4）：DOWN=故障排除、AVAILABLE=恢复回归，其余状态只回写不触发。 */
    public static final Set<String> RESCHEDULE_TRIGGER_STATUSES =
            Set.of(StatusConstants.DOWN_RESOURCE_STATUS, StatusConstants.AVAILABLE_RESOURCE_STATUS);

    /** pending 重排标记 key（planning 服务命名空间前缀，ADR-0005 §4）。 */
    public static final String PENDING_MARKER_KEY = "planning:reschedule:pending";

    /** 标记 TTL（防泄漏上界；正常情况下 sweeper 节拍远短于此）。 */
    private static final Duration PENDING_MARKER_TTL = Duration.ofMinutes(30);

    /** 比较清除脚本：只清对应置标时间戳的标记——并发事件重复置标时不误清新标记（防丢重排）。 */
    private static final DefaultRedisScript<Long> CLEAR_IF_VALUE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final IScheduleJobService scheduleJobService;

    public RescheduleTriggerService(StringRedisTemplate redisTemplate,
                                    IScheduleJobService scheduleJobService) {
        this.redisTemplate = redisTemplate;
        this.scheduleJobService = scheduleJobService;
    }

    /**
     * 请求一次全量重排（消费侧触发入口）：置 pending 标记 → 立即尝试提交全量重排。
     *
     * <p>异常分级：互斥拒绝/意外异常均吞掉（标记已在，sweeper 兜底排空）；置标失败仅
     * 记录错误并仍尝试立即提交（尽力而为；无标记则本次触发无持久化兜底，靠后续事件或
     * 人工触发收敛）。本方法不向上抛错。</p>
     */
    public void requestReschedule() {
        String markerValue = markPending();
        try {
            Long jobId = scheduleJobService.scheduleAllAsync(defaultAssignment());
            clearPendingMarker(markerValue);
            log.info("资源状态事件触发全量重排: jobId={}（pending 标记已清除）", jobId);
        } catch (ServiceException rejected) {
            // 预期分支：提交互斥（已有排程在跑）→ 标记保留，等待 sweeper 空闲节拍排空
            log.info("重排提交被排程互斥拒绝（pending 标记保留，等待 sweeper 排空）: {}", rejected.getMessage());
        } catch (Exception ex) {
            // 意外异常同样不传播：回写已成功，事件不应因触发层问题进入重试/DLX
            log.error("重排提交失败（pending 标记保留，等待 sweeper 排空）", ex);
        }
    }

    /**
     * sweeper 排空（既有清扫节拍内追加的一步）：pending 标记存在且当前无
     * PENDING/RUNNING 排程任务时尝试补跑全量重排，成功清标记；仍有任务在跑或提交
     * 被互斥拒绝/异常 → 标记保留等下一拍（互斥兜底仍在）。
     *
     * @return 本次是否排空（补跑提交成功并清除标记）
     */
    public boolean drainPendingIfIdle() {
        String markerValue;
        try {
            markerValue = redisTemplate.opsForValue().get(PENDING_MARKER_KEY);
        } catch (Exception ex) {
            log.error("重排 pending 标记读取失败（本拍跳过，标记 TTL 兜底）", ex);
            return false;
        }
        if (markerValue == null) {
            return false;
        }
        long activeJobs = Db.lambdaQuery(ScheduleJob.class)
                .in(ScheduleJob::getStatus,
                        StatusConstants.PENDING_SCHEDULE_JOB, StatusConstants.RUNNING_SCHEDULE_JOB)
                .count();
        if (activeJobs > 0) {
            log.info("重排 pending 标记存在但仍有排程任务在跑（activeJobs={}），标记保留等下一拍", activeJobs);
            return false;
        }
        try {
            Long jobId = scheduleJobService.scheduleAllAsync(defaultAssignment());
            clearPendingMarker(markerValue);
            log.info("sweeper 排空重排 pending 标记并补跑全量重排: jobId={}", jobId);
            return true;
        } catch (ServiceException rejected) {
            log.info("sweeper 补跑被排程互斥拒绝（标记保留，互斥兜底仍在）: {}", rejected.getMessage());
            return false;
        } catch (Exception ex) {
            log.error("sweeper 补跑失败（标记保留，等下一拍）", ex);
            return false;
        }
    }

    /** 置 pending 标记（value=置标时间戳，重复事件刷新 TTL；失败仅记录，不阻断提交尝试）。 */
    private String markPending() {
        String value = String.valueOf(System.currentTimeMillis());
        try {
            redisTemplate.opsForValue().set(PENDING_MARKER_KEY, value, PENDING_MARKER_TTL);
        } catch (Exception ex) {
            log.error("重排 pending 标记写入失败（本次触发无持久化兜底，仅记录）", ex);
        }
        return value;
    }

    /** 清除 pending 标记（比较清除：并发事件重复置标时保留更新标记，交由后续排空）。 */
    private void clearPendingMarker(String expectedValue) {
        try {
            redisTemplate.execute(CLEAR_IF_VALUE_SCRIPT, List.of(PENDING_MARKER_KEY), expectedValue);
        } catch (Exception ex) {
            log.warn("重排 pending 标记清除失败（TTL 到期自动清理）: key={}", PENDING_MARKER_KEY, ex);
        }
    }

    /** 默认排程参数（全部字段为空：排所有 READY 任务、以当前时间为基准）——与人工入口空参数同语义。 */
    private TaskAssignmentDTO defaultAssignment() {
        return new TaskAssignmentDTO();
    }
}
