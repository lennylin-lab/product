package com.product.planning.dto;

import lombok.Getter;

/**
 * 排程执行结果
 *
 * <p>封装排程执行后的结果信息，用于：
 * <ul>
 *   <li>同步排程：返回成功/失败状态给调用方</li>
 *   <li>异步排程：更新 ScheduleJob 的执行结果和统计数据</li>
 *   <li>日志记录：记录排程的关键指标（任务数、批次数、耗时等）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 成功结果
 * ScheduleExecutionResult result = ScheduleExecutionResult.success(1000, 5);
 * // result.isSuccess() = true
 * // result.getTotalTaskCount() = 1000
 * // result.getBatchCount() = 5
 *
 * // 失败结果（无任务数）
 * ScheduleExecutionResult result = ScheduleExecutionResult.failure("没有可用机台");
 * // result.isSuccess() = false
 * // result.getErrorMessage() = "没有可用机台"
 *
 * // 失败结果（带任务数）
 * ScheduleExecutionResult result = ScheduleExecutionResult.failure("排程结果落库失败", 1000, 3);
 * // result.isSuccess() = false
 * // result.getTotalTaskCount() = 1000（已处理任务数）
 * // result.getBatchCount() = 3（已完成批次数）
 * }</pre>
 */
@Getter
public class ScheduleExecutionResult {
    /**
     * 是否成功
     * <ul>
     *   <li>true: 排程成功完成</li>
     *   <li>false: 排程失败（可能是计算失败或落库失败）</li>
     * </ul>
     */
    private final boolean success;

    /**
     * 错误信息
     * <p>仅在 success = false 时有值，记录失败原因：
     * <ul>
     *   <li>"没有可用机台"</li>
     *   <li>"任务已存在派工记录: xxx"</li>
     *   <li>"任务状态更新失败，预期更新N条，实际更新M条"</li>
     *   <li>"排程结果落库失败"</li>
     * </ul>
     */
    private final String errorMessage;

    /**
     * 处理的任务总数
     * <p>仅在成功时有意义，表示成功排程的任务数量
     */
    private final int totalTaskCount;

    /**
     * 处理的批次总数
     * <p>仅在成功时有意义，表示分批处理的批次数量
     * <p>计算公式：batchCount = ceil(totalTaskCount / batchSize)
     */
    private final int batchCount;

    /**
     * 构造排程执行结果
     *
     * @param success       是否成功
     * @param errorMessage  错误信息（失败时）
     * @param totalTaskCount 处理的任务总数
     * @param batchCount    处理的批次总数
     */
    public ScheduleExecutionResult(boolean success, String errorMessage, int totalTaskCount, int batchCount) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.totalTaskCount = totalTaskCount;
        this.batchCount = batchCount;
    }

    /**
     * 创建成功结果
     *
     * @param totalTaskCount 处理的任务总数
     * @param batchCount    处理的批次总数
     * @return 成功的排程执行结果
     */
    public static ScheduleExecutionResult success(int totalTaskCount, int batchCount) {
        return new ScheduleExecutionResult(true, null, totalTaskCount, batchCount);
    }

    /**
     * 创建失败结果（无任务数）
     *
     * <p>适用于准备阶段失败（如没有可用机台），此时尚未处理任何任务
     *
     * @param errorMessage 错误信息
     * @return 失败的排程执行结果（任务数和批次数为0）
     */
    public static ScheduleExecutionResult failure(String errorMessage) {
        return new ScheduleExecutionResult(false, errorMessage, 0, 0);
    }

    /**
     * 创建失败结果（带任务数）
     *
     * <p>适用于落库阶段失败，此时已完成计算但入库失败
     * <p>记录已处理的任务数和批次数，便于问题定位和日志分析
     *
     * @param errorMessage   错误信息
     * @param totalTaskCount 已处理的任务总数
     * @param batchCount     已处理的批次总数
     * @return 失败的排程执行结果（带任务数）
     */
    public static ScheduleExecutionResult failure(String errorMessage, int totalTaskCount, int batchCount) {
        return new ScheduleExecutionResult(false, errorMessage, totalTaskCount, batchCount);
    }
}
