package com.product.planning.domain.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 排程请求参数。
 */
@Data
public class TaskAssignmentDTO {
    /** 指定排程的任务ID（可选，为空时排程所有 READY 任务） */
    private Long taskId;
    /** 排程开始时间基准（可选，为空时使用当前时间） */
    @DateTimeFormat(pattern = "yyyy-MM-dd[' 'HH:mm:ss']")
    private LocalDateTime assignmentStart;
    /**
     * 排程策略。
     * 可选值：EARLIEST_START、EARLIEST_FINISH、DUE_DATE_PRIORITY。
     */
    private String scheduleStrategy;
}
