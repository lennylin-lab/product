package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 夹模兼容矩阵对象 fixture_mold_compatibility（2026-09-15 夹具兼容任务新增，
 * 结构镜像 {@link MachineMoldCompatibility}）
 *
 * @author product
 * @date 2026-09-15
 */
@Data
@TableName("fixture_mold_compatibility")
public class FixtureMoldCompatibility {
    private static final long serialVersionUID = 1L;

    /** 夹具ID */
    @TableField("fixture_id")
    private Long fixtureId;

    /** 模具ID */
    @TableField("mold_id")
    private Long moldId;

    /** 是否兼容 */
    @TableField("is_compatible")
    private Integer isCompatible;
}
