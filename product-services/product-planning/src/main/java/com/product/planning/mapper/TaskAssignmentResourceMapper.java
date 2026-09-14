package com.product.planning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.planning.domain.entity.TaskAssignmentResource;
import com.product.planning.dto.ResourceRuntimeStatsDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 派工资源占用明细 Mapper。
 */
@Mapper
public interface TaskAssignmentResourceMapper extends BaseMapper<TaskAssignmentResource> {

    /**
     * 批量插入派工资源占用明细。
     */
    int batchInsert(@Param("rows") List<TaskAssignmentResource> rows);

    /**
     * 批量查询多资源的运行时统计信息。
     *
     * <p>
     * 按 (resourceType, resourceId) 维度聚合 task_assignment_resource 表中
     * 活跃派工记录的最近结束时间和最大序号，用于构建 ResourceRuntimeContext。
     * </p>
     *
     * @param resourceTypes 资源类型列表
     * @return 资源运行时统计列表
     */
    List<ResourceRuntimeStatsDTO> selectResourceRuntimeStats(@Param("resourceTypes") List<String> resourceTypes);

    /**
     * 按任务 ID 批量删除派工资源占用明细。
     */
    int deleteByTaskIds(@Param("taskIds") List<Long> taskIds);
}
