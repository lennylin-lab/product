package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.masterdata.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 路线工序定义对象 route_operation
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode
@TableName("route_operation")
public class RouteOperation {

    @TableId(value = "op_id", type = IdType.ASSIGN_ID)
    private Long opId;

    /** 所属路线ID */
    @Excel(name = "路线ID")
    @TableField("route_id")
    private Long routeId;

    /** 工序编码：SETUP/INJECT/POST_QC_PUTAWAY */
    @Excel(name = "工序编码")
    @TableField("op_code")
    private String opCode;

    /** 工序顺序（1/2/3） */
    @Excel(name = "工序顺序")
    @TableField("sequence")
    private Integer sequence;

    /** 可用资源规则引用 */
    @TableField("eligible_resource_rule")
    private String eligibleResourceRule;

    /** 标准工时模型引用 */
    @TableField("std_time_model")
    private String stdTimeModel;

    /** 排队策略 */
    @TableField("queue_policy")
    private String queuePolicy;
}
