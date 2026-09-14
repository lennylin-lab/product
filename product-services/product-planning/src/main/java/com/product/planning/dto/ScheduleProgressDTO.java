package com.product.planning.dto;

import lombok.Data;

/**
 * 排程进度快照
 *
 * <p>该对象用于在排程计算过程中实时回传进度信息，支持：
 * <ul>
 *   <li>异步排程场景：通过 Consumer 回调推送进度</li>
 *   <li>进度轮询场景：更新 schedule_job 表的进度字段</li>
 *   <li>前端展示：实时显示排程进度条和当前阶段</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 在 runScheduleAll 方法中推送进度
 * notifyScheduleProgress(progressConsumer, 1000, 200, 1, 18, SchedulePhase.STARTED);
 * // → 前端收到：总任务1000，已处理200，批次1，进度18%，阶段STARTED
 *
 * // 在 executeScheduleJob 方法中更新数据库
 * scheduleJob.setProcessedTaskCount(progress.getProcessedTaskCount());
 * scheduleJob.setProgressPercent(progress.getProgressPercent());
 * scheduleJob.setPhase(progress.getPhase());
 * }</pre>
 *
 * <p>进度推送时机：
 * <ul>
 *   <li>NO_TASK: 没有待排程任务时（进度100%）</li>
 *   <li>STARTED: 开始排程前（进度0%）</li>
 *   <li>CALCULATING: 每批计算完成后（进度0-90%）</li>
 *   <li>SUCCESS: 全部成功入库后（进度100%）</li>
 *   <li>FAILED: 任何步骤失败时</li>
 * </ul>
 *
 * @see SchedulePhase 排程阶段枚举
 * @see ScheduleExecutionResult 排程执行结果
 */
@Data
public class ScheduleProgressDTO {

    /**
     * 总任务数
     * <p>本次排程需要处理的任务总数（READY 状态的任务数）
     */
    private int totalTaskCount;

    /**
     * 已处理任务数
     * <p>当前已完成计算的任务数量（取值范围：0 ~ totalTaskCount）
     */
    private int processedTaskCount;

    /**
     * 已完成批次数
     * <p>当前已完成的批次数量（用于日志记录和监控）
     */
    private int batchCount;

    /**
     * 当前进度百分比
     * <p>排程整体进度（取值范围：0-100）
     * <ul>
     *   <li>0-90%: 计算阶段（按已处理任务数比例）</li>
     *   <li>90-100%: 落库阶段</li>
     *   <li>100%: 完成</li>
     * </ul>
     */
    private int progressPercent;

    /**
     * 当前阶段描述
     * <p>排程当前所处的阶段，对应 {@link SchedulePhase} 的 code：
     * <ul>
     *   <li>"NO_TASK": 没有待排程任务</li>
     *   <li>"STARTED": 排程已启动</li>
     *   <li>"CALCULATING": 计算中</li>
     *   <li>"SUCCESS": 排程成功</li>
     *   <li>"FAILED": 排程失败</li>
     * </ul>
     */
    private String phase;
}
