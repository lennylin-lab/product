package com.product.planning.domain.model;

import lombok.Data;

/**
 * 班次日历排程视图（Phase 4）：与单体 Calendar 实体同构的纯内存模型
 * （calendar 归 product-master-data，经 getCalendars 契约加载映射）。
 */
@Data
public class Calendar {

    private Long calendarId;

    private String calendarName;

    /** 工作日模式（如 Mon-Fri；与单体 workday_pattern 语义一致）。 */
    private String workdayPattern;

    /** 班次开始（HH:mm）。 */
    private String shiftStart;

    /** 班次结束（HH:mm）。 */
    private String shiftEnd;
}
