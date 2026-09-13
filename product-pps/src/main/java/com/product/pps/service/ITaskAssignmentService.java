package com.product.pps.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.domain.dto.TaskAssignmentDTO;
import com.product.domain.entity.ScheduleJob;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.vo.TaskAssignmentVO;
import com.product.pps.dto.ScheduleExecutionResult;
import com.product.pps.dto.ScheduleProgressDTO;

import java.util.List;

/**
 * 派工/排程结果Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-02
 */
public interface ITaskAssignmentService extends IService<TaskAssignment> {

    /**
     * 查询派工/排程结果
     *
     * @param assignmentId 派工/排程结果主键
     * @return 派工/排程结果
     */
    TaskAssignment selectTaskAssignmentByAssignmentId(Long assignmentId);

    /**
     * 查询派工/排程结果列表
     *
     * @param taskAssignment 查询条件
     * @return 派工/排程结果集合
     */
    List<TaskAssignment> selectTaskAssignmentList(TaskAssignment taskAssignment);

    /**
     * 分页查询派工/排程结果列表
     *
     * @param page      分页参数
     * @param taskAssignment 查询条件
     * @return 分页结果
     */
    Page<TaskAssignmentVO> selectTaskAssignmentPage(Page<TaskAssignmentVO> page, TaskAssignment taskAssignment);

    /**
     * 新增派工/排程结果
     *
     * @param taskAssignment 派工/排程结果
     * @return 是否成功
     */
    boolean insertTaskAssignment(TaskAssignment taskAssignment);

    /**
     * 批量新增派工/排程结果
     *
     * @param taskAssignments 派工/排程结果列表
     * @return 成功条数
     */
    int batchInsertTaskAssignment(List<TaskAssignment> taskAssignments);

    /**
     * 修改派工/排程结果
     *
     * @param taskAssignment 派工/排程结果
     * @return 是否成功
     */
    boolean updateTaskAssignment(TaskAssignment taskAssignment);

    /**
     * 批量删除派工/排程结果
     *
     * @param assignmentIds 主键集合
     * @return 是否成功
     */
    boolean deleteTaskAssignmentByAssignmentIds(Long[] assignmentIds);

    /**
     * 删除派工/排程结果信息
     *
     * @param assignmentId 主键
     * @return 是否成功
     */
    boolean deleteTaskAssignmentByAssignmentId(Long assignmentId);

    boolean schedule(TaskAssignmentDTO taskAssignmentDTO);

    boolean scheduleAll(TaskAssignmentDTO taskAssignmentDTO);

    /**
     * 执行全量排程计划，并在计算过程中回传进度快照。
     *
     * @param taskAssignmentDTO 排程参数
     * @param progressConsumer   进度回调（可为空）
     * @return 排程执行结果
     */
    ScheduleExecutionResult executeSchedulePlan(TaskAssignmentDTO taskAssignmentDTO,
                                                java.util.function.Consumer<ScheduleProgressDTO> progressConsumer);

    Long scheduleAllAsync(TaskAssignmentDTO taskAssignmentDTO);

    ScheduleJob selectScheduleJobByJobId(Long jobId);

    Page<ScheduleJob> selectScheduleJobPage(Page<ScheduleJob> page, ScheduleJob scheduleJob);
}
