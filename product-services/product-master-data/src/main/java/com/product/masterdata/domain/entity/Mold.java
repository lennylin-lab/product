package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.masterdata.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 模具扩展信息对象 mold
 *
 * @author product
 * @date 2026-04-25
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode
@TableName("mold")
public class Mold {
    private static final long serialVersionUID = 1L;

    /** 模具ID */
    @TableId(value = "mold_id", type = IdType.ASSIGN_ID)
    private Long moldId;

    /** 模具业务编号 */
    @Excel(name = "模具业务编号")
    @TableField("mold_code")
    private String moldCode;

    /** 型腔数 */
    @Excel(name = "型腔数")
    @TableField("cavity")
    private Integer cavity;

    /** 模具状态 */
    @Excel(name = "模具状态", readConverterExp = "AVAILABLE=可用,IN_USE=使用中,REPAIR=维修中,MAINTENANCE=保养中")
    @TableField("mold_status")
    private String moldStatus;

    /** 下次保养到期日 */
    @Excel(name = "下次保养到期日", width = 20, dateFormat = "yyyy-MM-dd")
    @TableField("next_maint_due")
    private LocalDate nextMaintDue;
}
