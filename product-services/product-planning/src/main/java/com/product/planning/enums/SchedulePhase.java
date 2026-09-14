package com.product.planning.enums;

/**
 * 排程进度阶段枚举
 *
 * 说明：定义排程执行过程中的各个阶段，避免硬编码字符串
 */
public enum SchedulePhase {
    /** 未开始 */
    NOT_STARTED("NOT_STARTED"),
    /** 已启动 */
    STARTED("STARTED"),
    /** 计算中 */
    CALCULATING("CALCULATING"),
    /** 无任务 */
    NO_TASK("NO_TASK"),
    /** 成功 */
    SUCCESS("SUCCESS"),
    /** 失败 */
    FAILED("FAILED");

    private final String code;

    SchedulePhase(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
