package com.product.planning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.planning.domain.entity.TaskAssignment;
import com.product.planning.domain.vo.TaskAssignmentVO;
import com.product.planning.dto.MachineRuntimeStatsDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 派工/排程结果Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2026-01-02
 */
@Mapper
public interface TaskAssignmentMapper extends BaseMapper<TaskAssignment> {

    /**
     * 查询单台机的下一个可用时间（废弃，建议使用 selectMachineLatestEndTime）
     *
     * @param machineId  机台ID
     * @param statusList 任务状态列表
     * @return 该机台最近任务的结束时间
     * @deprecated 被 selectMachineLatestEndTime 替代，支持批量查询
     */
    @Deprecated
    LocalDateTime selectMachineNextTime(@Param("machineId") Long machineId,
                                        @Param("statusList") List<String> statusList);

    /**
     * 批量查询多台机的最近任务结束时间
     *
     * 用途：构建机台运行时上下文（内存快照）
     * - 预加载每台机的最早可用时间（前序任务结束时间）
     * - 新任务的开始时间不能早于该时间
     *
     * @param machineIds 机台ID列表
     * @param statusList 任务状态列表（SCHEDULED/RUNNING/PAUSED）
     * @return 每台机的最近任务结束时间（machineId -> latestEndTime）
     * @deprecated 被 selectMachineRuntimeStats 替代，支持一次性查询所有运行时信息
     */
    @Deprecated
    List<MachineRuntimeStatsDTO> selectMachineLatestEndTime(@Param("machineIds") List<Long> machineIds,
                                                            @Param("statusList") List<String> statusList);

    /**
     * 批量查询多台机的当前最大序号
     *
     * 用途：构建机台运行时上下文（内存快照）
     * - 预加载每台机的当前最大序号
     * - 新任务的序号 = 最大序号 + 1
     *
     * @param machineIds 机台ID列表
     * @return 每台机的最大序号（machineId -> maxSequence）
     * @deprecated 被 selectMachineRuntimeStats 替代，支持一次性查询所有运行时信息
     */
    @Deprecated
    List<MachineRuntimeStatsDTO> selectMachineMaxSequence(@Param("machineIds") List<Long> machineIds);

    /**
     * 批量查询多台机的运行时统计信息（合并查询）
     *
     * 用途：构建机台运行时上下文（内存快照）
     * - 一次性查询每台机的：1)最近任务结束时间 2)当前最大序号
     * - 避免两次独立的数据库查询，提升性能
     *
     * @param machineIds 机台ID列表
     * @param statusList 任务状态列表（SCHEDULED/RUNNING/PAUSED）
     * @return 每台机的运行时统计信息（machineId, latestEndTime, maxSequence）
     */
    List<MachineRuntimeStatsDTO> selectMachineRuntimeStats(@Param("machineIds") List<Long> machineIds,
                                                           @Param("statusList") List<String> statusList);

    /**
     * 查询已存在的任务ID（防重检查）
     *
     * 用途：代码侧防重校验（与数据库唯一索引配合形成双重保障）
     * - 在插入派工记录前检查任务是否已被派工
     * - 提前发现冲突，避免数据库异常
     *
     * @param taskIds 要检查的任务ID列表
     * @return 已存在的任务ID列表
     */
    List<Long> selectExistingTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 分页查询派工记录（关联工序任务信息）
     *
     * @param page            分页参数
     * @param taskAssignment 查询条件
     * @return 分页结果（包含工序任务的 batchId、opCode 等字段）
     */
    Page<TaskAssignmentVO> selectTaskAssignmentPage(Page<TaskAssignmentVO> page,
                                                    @Param("taskAssignment") TaskAssignment taskAssignment);
}
