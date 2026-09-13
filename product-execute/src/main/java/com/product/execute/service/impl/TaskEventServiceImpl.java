package com.product.execute.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.constant.StatusConstants;
import com.product.common.constant.TaskEventConstants;
import com.product.common.utils.StringUtils;
import com.product.core.status.StatusEntityType;
import com.product.core.status.StatusRefreshContext;
import com.product.core.status.StatusRefreshService;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskEvent;
import com.product.execute.mapper.TaskEventMapper;
import com.product.execute.service.ITaskEventService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 任务事件日志（全流程追溯核心）Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-03
 */
@Service
public class TaskEventServiceImpl extends ServiceImpl<TaskEventMapper, TaskEvent> implements ITaskEventService {
    @Autowired
    private StatusRefreshService statusRefreshService;

    /**
     * 查询任务事件日志（全流程追溯核心）
     *
     * @param eventId 任务事件日志（全流程追溯核心）主键
     * @return 任务事件日志（全流程追溯核心）
     */
    @Override
    public TaskEvent selectTaskEventByEventId(Long eventId) {
        return getById(eventId);
    }

    /**
     * 查询任务事件日志（全流程追溯核心）列表
     *
     * @param taskEvent 查询条件
     * @return 任务事件日志（全流程追溯核心）集合
     */
    @Override
    public List<TaskEvent> selectTaskEventList(TaskEvent taskEvent) {
        return list(buildQueryWrapper(taskEvent));
    }

    /**
     * 分页查询任务事件日志（全流程追溯核心）列表
     *
     * @param page      分页参数
     * @param taskEvent 查询条件
     * @return 分页结果
     */
    @Override
    public Page<TaskEvent> selectTaskEventPage(Page<TaskEvent> page, TaskEvent taskEvent) {
        return this.page(page, buildQueryWrapper(taskEvent));
    }

    /**
     * 新增任务事件日志（全流程追溯核心）
     *
     * @param taskEvent 任务事件日志（全流程追溯核心）
     * @return 是否成功
     */
    @Override
    public boolean insertTaskEvent(TaskEvent taskEvent) {
        if (taskEvent.getEventId() == null) {
            taskEvent.setEventId(IdWorker.getId());
        }
        boolean saved = save(taskEvent);
        return saved;
    }

    /**
     * 批量新增任务事件日志（全流程追溯核心）
     *
     * @param taskEvents 任务事件日志（全流程追溯核心）列表
     * @return 成功条数
     */
    @Override
    public int batchInsertTaskEvent(List<TaskEvent> taskEvents) {
        if (CollectionUtils.isEmpty(taskEvents)) {
            return 0;
        }
        taskEvents.forEach(item -> {
            if (item.getEventId() == null) {
                item.setEventId(IdWorker.getId());
            }
        });
        boolean success = saveBatch(taskEvents);
        return success ? taskEvents.size() : 0;
    }

    /**
     * 修改任务事件日志（全流程追溯核心）
     *
     * @param taskEvent 任务事件日志（全流程追溯核心）
     * @return 是否成功
     */
    @Override
    public boolean updateTaskEvent(TaskEvent taskEvent) {
        boolean updated = updateById(taskEvent);
        return updated;
    }

    /**
     * 批量删除任务事件日志（全流程追溯核心）
     *
     * @param eventIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteTaskEventByEventIds(String[] eventIds) {
        if (eventIds == null || eventIds.length == 0) {
            return false;
        }
        return removeByIds(Arrays.asList(eventIds));
    }

    /**
     * 删除任务事件日志（全流程追溯核心）信息
     *
     * @param eventId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteTaskEventByEventId(Long eventId) {
        return removeById(eventId);
    }

    /** 开始任务：将任务状态变更为 RUNNING，并记录开始事件 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean start(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.RUNNING_OPERATION_TASK,
                TaskEventConstants.START_TASK_EVENT);
    }

    /** 暂停任务：将任务状态变更为 PAUSED，并记录暂停事件 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pause(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.PAUSED_OPERATION_TASK,
                TaskEventConstants.PAUSE_TASK_EVENT);
    }

    /** 恢复任务：将任务状态变更为 RUNNING，并记录恢复事件 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean resume(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.RUNNING_OPERATION_TASK,
                TaskEventConstants.RESUME_TASK_EVENT);
    }

    /** 完成任务：将任务状态变更为 DONE，并记录完成事件，同时级联刷新生产批次状态 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.DONE_OPERATION_TASK,
                TaskEventConstants.FINISH_TASK_EVENT);
    }

    /**
     * 任务状态变更的核心流程：更新任务状态 → 记录事件日志 → 级联刷新业务状态。
     *
     * @return 三个步骤全部成功返回 true，任一步骤失败返回 false
     */
    boolean changeTaskStateAndRecordEvent(Long taskId, String targetStatus, String eventType) {
        if (taskId == null || StringUtils.isEmpty(targetStatus) || StringUtils.isEmpty(eventType)) {
            return false;
        }
        boolean updated = updateTaskStatus(taskId, targetStatus);
        if (!updated) {
            return false;
        }
        TaskEvent taskEvent = new TaskEvent();
        taskEvent.setTaskId(taskId);
        taskEvent.setEventType(eventType);
        taskEvent.setEventTime(LocalDateTime.now());
        taskEvent.setResourceId(loadMachineIdByTaskId(taskId));
        boolean saved = save(taskEvent);
        if (!saved) {
            return false;
        }
        return refreshRelatedBusinessStatus(taskId);
    }

    /** 更新工序任务状态 */
    protected boolean updateTaskStatus(Long taskId, String targetStatus) {
        return Db.lambdaUpdate(OperationTask.class)
                .set(OperationTask::getStatus, targetStatus)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    /** 根据任务ID查询其派工记录中的机台资源ID，用于事件日志关联 */
    protected Long loadMachineIdByTaskId(Long taskId) {
        TaskAssignment assignment = Db.lambdaQuery(TaskAssignment.class)
                .select(TaskAssignment::getMachineId)
                .eq(TaskAssignment::getTaskId, taskId)
                .last("limit 1")
                .one();
        return assignment == null ? null : assignment.getMachineId();
    }

    /** 刷新关联的生产批次状态：通过任务找到所属批次，触发级联状态刷新（批次→订单行→客户订单） */
    protected boolean refreshRelatedBusinessStatus(Long taskId) {
        Long batchId = loadBatchIdByTaskId(taskId);
        if (batchId == null) {
            return true;
        }
        triggerStatusRefresh(batchId);
        return true;
    }

    /** 根据任务ID查询其所属的生产批次ID */
    protected Long loadBatchIdByTaskId(Long taskId) {
        OperationTask task = Db.lambdaQuery(OperationTask.class)
                .select(OperationTask::getBatchId)
                .eq(OperationTask::getTaskId, taskId)
                .last("limit 1")
                .one();
        return task == null ? null : task.getBatchId();
    }

    /** 触发状态刷新链路：生产批次 → 订单行 → 客户订单 */
    protected void triggerStatusRefresh(Long batchId) {
        statusRefreshService.refresh(StatusRefreshContext.of(StatusEntityType.PRODUCTION_BATCH, batchId));
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<TaskEvent> buildQueryWrapper(TaskEvent taskEvent) {
        LambdaQueryWrapper<TaskEvent> wrapper = new LambdaQueryWrapper<>();
        if (taskEvent == null) {
            return wrapper;
        }
        wrapper.eq(taskEvent.getTaskId() != null, TaskEvent::getTaskId, taskEvent.getTaskId());
        wrapper.eq(taskEvent.getEventType() != null, TaskEvent::getEventType, taskEvent.getEventType());
        wrapper.eq(taskEvent.getEventTime() != null, TaskEvent::getEventTime, taskEvent.getEventTime());
        wrapper.eq(taskEvent.getResourceId() != null, TaskEvent::getResourceId, taskEvent.getResourceId());
        wrapper.eq(taskEvent.getRemark() != null, TaskEvent::getRemark, taskEvent.getRemark());
        return wrapper;
    }
}
