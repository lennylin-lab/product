package com.product.execution.common.constant;

/**
 * 任务事件类型常量（单体 product-common TaskEventConstants 的服务化移植）。
 *
 * <p>单体移植的四值（START/PAUSE/RESUME/FINISH）语义与值冻结，零变化；
 * {@link #EXCEPTION_TASK_EVENT} 为 2026-09-16 异常事件建模增量（KD1：只加不改，
 * 异常上报后任务复用既有 PAUSED 状态，不新增状态值）。</p>
 */
public class TaskEventConstants {
    public static final String START_TASK_EVENT = "START";
    public static final String PAUSE_TASK_EVENT = "PAUSE";
    public static final String RESUME_TASK_EVENT = "RESUME";
    public static final String FINISH_TASK_EVENT = "FINISH";

    /** 任务异常（KD1 增量：目标状态复用 PAUSED；原因落 task_event.reason_code/remark）。 */
    public static final String EXCEPTION_TASK_EVENT = "EXCEPTION";
}
