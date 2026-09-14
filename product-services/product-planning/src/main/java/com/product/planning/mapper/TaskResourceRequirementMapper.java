package com.product.planning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.planning.domain.entity.TaskResourceRequirement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务资源需求 Mapper（Phase 4）。
 *
 * <p>单体中该实体无注册 Mapper、依赖 MyBatis-Plus Db 工具链的隐式行为（其
 * TableInfoCache 在部分路径抛 Not Found TableInfoCache，属单体冻结缺陷类）；
 * 服务侧显式注册 Mapper，保证 Db.lambdaQuery/saveBatch 与排程快照装配稳定可用。</p>
 */
@Mapper
public interface TaskResourceRequirementMapper extends BaseMapper<TaskResourceRequirement> {
}
