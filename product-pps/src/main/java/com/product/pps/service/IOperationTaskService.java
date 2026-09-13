package com.product.pps.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.common.core.result.AjaxResult;
import com.product.domain.entity.OperationTask;

import java.util.List;

/**
 * 工序任务Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-31
 */
public interface IOperationTaskService extends IService<OperationTask> {

    /**
     * 查询工序任务
     *
     * @param taskId 工序任务主键
     * @return 工序任务
     */
    OperationTask selectOperationTaskByTaskId(Long taskId);

    /**
     * 查询工序任务列表
     *
     * @param operationTask 查询条件
     * @return 工序任务集合
     */
    List<OperationTask> selectOperationTaskList(OperationTask operationTask);

    /**
     * 分页查询工序任务列表
     *
     * @param page      分页参数
     * @param operationTask 查询条件
     * @return 分页结果
     */
    Page<OperationTask> selectOperationTaskPage(Page<OperationTask> page, OperationTask operationTask);

    /**
     * 新增工序任务
     *
     * @param operationTask 工序任务
     * @return 是否成功
     */
    boolean insertOperationTask(OperationTask operationTask);

    /**
     * 批量新增工序任务
     *
     * @param operationTasks 工序任务列表
     * @return 成功条数
     */
    int batchInsertOperationTask(List<OperationTask> operationTasks);

    /**
     * 修改工序任务
     *
     * @param operationTask 工序任务
     * @return 是否成功
     */
    boolean updateOperationTask(OperationTask operationTask);

    /**
     * 批量删除工序任务
     *
     * @param taskIds 主键集合
     * @return 是否成功
     */
    boolean deleteOperationTaskByTaskIds(String[] taskIds);

    /**
     * 删除工序任务信息
     *
     * @param taskId 主键
     * @return 是否成功
     */
    boolean deleteOperationTaskByTaskId(Long taskId);

    AjaxResult generateTask(List<Long> batchIds);

    AjaxResult retryGenerateTask(Long batchId);

    boolean cancel(Long taskId);

    boolean restore(Long taskId);

    boolean revokeSchedule(Long taskId);

    Page<OperationTask> selectReadyAndScheduledPage(Page<OperationTask> page, OperationTask operationTask);
}
