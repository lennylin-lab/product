package com.product.planning.common.constant;

/**
 * @Auther: chuan
 * @Date: 2025/12/31 - 12 - 31 - 16:40
 * @Description: com.product.planning.common.constant
 * @version: 1.0
 */
public class StatusConstants {
    public static final String NEW_CUSTOMER_ORDER = "NEW";
    public static final String CONFIRMED_CUSTOMER_ORDER = "CONFIRMED";
    public static final String IN_PRODUCTION_CUSTOMER_ORDER = "IN_PRODUCTION";
    public static final String DONE_CUSTOMER_ORDER = "DONE";

    public static final String NEW_ORDER_LINE = "NEW";
    public static final String RELEASED_ORDER_LINE = "RELEASED";
    public static final String IN_PRODUCTION_ORDER_LINE = "IN_PRODUCTION";
    public static final String DONE_ORDER_LINE = "DONE";

    public static final String PLANNED_PRODUCTION_BATCH = "PLANNED";
    public static final String RELEASED_PRODUCTION_BATCH = "RELEASED";
    public static final String IN_PROCESS_PRODUCTION_BATCH = "IN_PROCESS";
    public static final String DONE_PRODUCTION_BATCH = "DONE";

    public static final String READY_OPERATION_TASK = "READY";
    public static final String SCHEDULED_OPERATION_TASK = "SCHEDULED";
    public static final String RUNNING_OPERATION_TASK = "RUNNING";
    public static final String PAUSED_OPERATION_TASK = "PAUSED";
    public static final String DONE_OPERATION_TASK = "DONE";
    public static final String CANCELLED_OPERATION_TASK = "CANCELLED";

    public static final String AVAILABLE_RESOURCE_STATUS ="AVAILABLE";
    public static final String BUSY_RESOURCE_STATUS ="BUSY";
    public static final String DOWN_RESOURCE_STATUS ="DOWN";
    public static final String MAINTENANCE_RESOURCE_STATUS ="MAINTENANCE";
    public static final String OFFSHIFT_RESOURCE_STATUS ="OFFSHIFT";

    public static final String PENDING_SCHEDULE_JOB = "PENDING";
    public static final String RUNNING_SCHEDULE_JOB = "RUNNING";
    public static final String SUCCESS_SCHEDULE_JOB = "SUCCESS";
    public static final String FAILED_SCHEDULE_JOB = "FAILED";
}
