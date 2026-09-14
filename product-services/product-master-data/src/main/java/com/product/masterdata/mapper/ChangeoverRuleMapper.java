package com.product.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.masterdata.domain.entity.ChangeoverRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 换型规则 Mapper（Phase 4：内部契约端点/排程换型规则读取）。
 */
@Mapper
public interface ChangeoverRuleMapper extends BaseMapper<ChangeoverRule> {
}
