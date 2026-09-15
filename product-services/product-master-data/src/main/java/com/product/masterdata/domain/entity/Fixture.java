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
 * 夹具扩展信息对象 fixture（2026-09-15 夹具建模任务新增）
 *
 * <p>扩展表主键 = resource.resource_id，与 machine/mold 扩展表同款约定；
 * fixture 资源主行仍由 resource 表承载（类型 FIXTURE）。</p>
 *
 * @author product
 * @date 2026-09-15
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode
@TableName("fixture")
public class Fixture {
    private static final long serialVersionUID = 1L;

    /** 夹具ID */
    @TableId(value = "fixture_id", type = IdType.ASSIGN_ID)
    private Long fixtureId;

    /** 夹具业务编号 */
    @Excel(name = "夹具业务编号")
    @TableField("fixture_code")
    private String fixtureCode;
}
