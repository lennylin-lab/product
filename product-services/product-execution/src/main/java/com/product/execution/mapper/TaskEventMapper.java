package com.product.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.execution.domain.entity.TaskEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务事件日志（全流程追溯核心）Mapper 接口（单体 product-execute TaskEventMapper 移植）。
 */
@Mapper
public interface TaskEventMapper extends BaseMapper<TaskEvent> {
}
