package com.product.planning.common.constant;

/**
 * 工艺路线工序引用常量（eligible_resource_rule / std_time_model）。
 */
public final class RouteOperationConstants {

    private RouteOperationConstants() {
    }

    /** 换模调机：人员 + 机台 */
    public static final String RULE_SETUP_MACHINE = "RULE_SETUP_MACHINE";
    /** 注塑：人员 + 机台 + 模具 */
    public static final String RULE_INJECT_MACHINE = "RULE_INJECT_MACHINE";
    /** 后处理/检验/入库：人员 + 工位 */
    public static final String RULE_POST_WORKSTATION = "RULE_POST_WORKSTATION";

    /** INJECT：A2 产能公式 */
    public static final String TM_INJECT_A2 = "TM_INJECT_A2";
    /** SETUP：固定基准时长（分钟/批，×批次数量） */
    public static final String TM_SETUP_BASE = "TM_SETUP_BASE";
    /** POST：单位工时（分钟/件，×批次数量） */
    public static final String TM_POST_UNIT = "TM_POST_UNIT";

    /** 排队策略：先进先出 */
    public static final String QUEUE_FIFO = "FIFO";
    /** 排队策略：同模优先同机台 */
    public static final String QUEUE_SAME_MOLD_FIRST = "SAME_MOLD_FIRST";
    /** 排队策略：最早交期优先（继承全局策略） */
    public static final String QUEUE_EDD = "EDD";

    public static final java.util.Set<String> QUEUE_POLICIES = java.util.Set.of(
            QUEUE_FIFO, QUEUE_SAME_MOLD_FIRST, QUEUE_EDD);
}
