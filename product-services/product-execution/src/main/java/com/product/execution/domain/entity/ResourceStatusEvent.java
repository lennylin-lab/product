package com.product.execution.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.execution.common.annotation.Excel;
import com.product.execution.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 资源状态事件对象 resource_status_event
 * （单体 product-domain ResourceStatusEvent 的服务化移植。单体现状仅存在实体与表，
 * 无写入/读取链路——冻结为事实；本服务提供内部登记端点并以 resource.status.changed
 * 事件出站（ADR-0004 §2：仅记录/告警用途），不发明单体不存在的外部行为。）
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("resource_status_event")
public class ResourceStatusEvent extends BaseEntity {

    @TableId(value = "event_id", type = IdType.ASSIGN_ID)
    private Long eventId;

    /** 资源ID */
    @Excel(name = "资源ID")
    @TableField("resource_id")
    private Long resourceId;

    /** 发生时间 */
    @Excel(name = "发生时间")
    @TableField("time")
    private LocalDateTime time;

    /** 原状态 */
    @TableField("from_status")
    private String fromStatus;

    /** 新状态 */
    @Excel(name = "新状态")
    @TableField("to_status")
    private String toStatus;

    /** 原因码 */
    @TableField("reason_code")
    private String reasonCode;

    /** 关联任务ID */
    @TableField("related_task_id")
    private Long relatedTaskId;
}
