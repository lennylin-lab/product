package com.product.planning.enums;

import java.util.Arrays;

/**
 * 排程策略。
 *
 * 说明：
 * - EARLIEST_START：优先选择最早开始的任务与机台
 * - EARLIEST_FINISH：优先选择最早完工的任务与机台
 * - DUE_DATE_PRIORITY：优先按交期和优先级排序任务
 * - LOWEST_COST：优先选择换型/准备成本最低的任务与机台
 */
public enum SchedulingStrategy {
    EARLIEST_START("EARLIEST_START"),
    EARLIEST_FINISH("EARLIEST_FINISH"),
    DUE_DATE_PRIORITY("DUE_DATE_PRIORITY"),
    LOWEST_COST("LOWEST_COST");

    private final String code;

    SchedulingStrategy(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 根据编码解析策略，匹配不到时默认返回 EARLIEST_START。
     */
    public static SchedulingStrategy fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return EARLIEST_START;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(EARLIEST_START);
    }
}
