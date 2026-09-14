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
 * 任务事件日志（全流程追溯核心）对象 task_event
 * （单体 product-domain TaskEvent 的服务化移植，字段与 @Excel 注解冻结）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("task_event")
public class TaskEvent extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 事件ID */
    @TableId(value = "event_id", type = IdType.ASSIGN_ID)
    private Long eventId;

    /** 任务ID */
    @Excel(name = "任务ID")
    @TableField(value = "task_id")
    private Long taskId;

    /** 事件类型（START：任务开始 PAUSE：任务暂停 RESUME：任务恢复 FINISH：任务完工） */
    @Excel(name = "事件类型", readConverterExp = "START=任务开始,PAUSE=任务暂停,RESUME=任务恢复,FINISH=任务完工")
    @TableField(value = "event_type")
    private String eventType;

    /** 事件时间 */
    @Excel(name = "事件时间")
    @TableField(value = "event_time")
    private LocalDateTime eventTime;

    /** 操作人ID */
    @TableField(value = "operator_id")
    private Long operatorId;

    /** 发生事件的资源（建议机台/工位） */
    @Excel(name = "发生事件的资源")
    @TableField(value = "resource_id")
    private Long resourceId;

    /** 良品数量 */
    @TableField(value = "qty_good")
    private Long qtyGood;

    /** 不良数量 */
    @TableField(value = "qty_bad")
    private Long qtyBad;

    /** 原因码 */
    @TableField(value = "reason_code")
    private String reasonCode;

    /** 备注 */
    @Excel(name = "备注")
    @TableField(value = "remark")
    private String remark;

}
