package com.product.execution.domain.dto;

import lombok.Data;

/**
 * 异常上报请求体（KD1 异常事件建模，2026-09-16 增量）：
 * {@code POST /execute/event/exception/{taskId}} 入参——reasonCode 必填（服务层校验，
 * 空白拒绝）、remark 可选；原因落 task_event.reason_code/remark，出站 payload 携带
 * 可选 reasonCode（v1 只加不改）。
 */
@Data
public class ExceptionReportDTO {

    /** 原因码（必填） */
    private String reasonCode;

    /** 备注（可选） */
    private String remark;
}
