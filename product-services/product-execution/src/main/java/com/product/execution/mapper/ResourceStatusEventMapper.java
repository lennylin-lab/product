package com.product.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.execution.domain.entity.ResourceStatusEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资源状态事件 Mapper 接口（单体现状无该 Mapper——执行域表归位后由本服务持有，ADR-0005）。
 */
@Mapper
public interface ResourceStatusEventMapper extends BaseMapper<ResourceStatusEvent> {
}
