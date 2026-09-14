package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 班次日历批量查询请求（Phase 4）。
 *
 * <p>{@code calendarIds} 为 null/空时返回全部日历（排程按资源 calendarId 批量取）；
 * 非空时受 {@link #MAX_IDS} 上限与去重校验约束（与产品/资源批量契约一致）。</p>
 */
public class CalendarBatchQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 单次批量 ID 上限（与产品/资源批量契约一致）。 */
    public static final int MAX_IDS = 1000;

    private List<Long> calendarIds;

    public CalendarBatchQueryRequest() {
    }

    public CalendarBatchQueryRequest(List<Long> calendarIds) {
        this.calendarIds = calendarIds;
    }

    public List<Long> getCalendarIds() {
        return calendarIds;
    }

    public void setCalendarIds(List<Long> calendarIds) {
        this.calendarIds = calendarIds;
    }
}
