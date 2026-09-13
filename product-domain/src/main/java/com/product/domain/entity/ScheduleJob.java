package com.product.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 排程任务记录对象 schedule_job
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("schedule_job")
public class ScheduleJob extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @TableId(value = "job_id", type = IdType.ASSIGN_ID)
    private Long jobId;

    @TableField("job_type")
    private String jobType;

    // PEDDING 待执行, RUNNING 执行中, SUCCESS 成功, FAILED 失败
    @TableField("status")
    private String status;

    @TableField("assignment_start")
    private LocalDateTime assignmentStart;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("total_task_count")
    private Integer totalTaskCount;

    @TableField("batch_count")
    private Integer batchCount;

    @TableField("scheduled_task_count")
    private Integer scheduledTaskCount;

    @TableField("processed_task_count")
    private Integer processedTaskCount;

    @TableField("progress_percent")
    private Integer progressPercent;

    @TableField("error_message")
    private String errorMessage;
}
