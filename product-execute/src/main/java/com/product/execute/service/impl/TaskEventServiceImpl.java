package com.product.execute.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.annotation.BizIdPrefix;
import com.product.common.constant.StatusConstants;
import com.product.common.constant.TaskEventConstants;
import com.product.common.utils.StringUtils;
import com.product.common.utils.uuid.IdUtils;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskEvent;
import com.product.execute.mapper.TaskEventMapper;
import com.product.execute.service.ITaskEventService;
import org.apache.commons.collections4.CollectionUtils;
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

    /**
     * 查询任务事件日志（全流程追溯核心）
     *
     * @param eventId 任务事件日志（全流程追溯核心）主键
     * @return 任务事件日志（全流程追溯核心）
     */
    @Override
    public TaskEvent selectTaskEventByEventId(String eventId) {
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
        if (StringUtils.isEmpty(taskEvent.getEventId())) {
            taskEvent.setEventId(buildBizId(taskEvent));
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
            if (StringUtils.isEmpty(item.getEventId())) {
                item.setEventId(buildBizId(item));
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
    public boolean deleteTaskEventByEventId(String eventId) {
        return removeById(eventId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean start(String taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.RUNNING_OPERATION_TASK,
                TaskEventConstants.START_TASK_EVENT);
    }

    @Override
    public boolean pause(String taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.PAUSED_OPERATION_TASK,
                TaskEventConstants.PAUSE_TASK_EVENT);
    }

    @Override
    public boolean resume(String taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.RUNNING_OPERATION_TASK,
                TaskEventConstants.RESUME_TASK_EVENT);
    }

    @Override
    public boolean complete(String taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                StatusConstants.DONE_OPERATION_TASK,
                TaskEventConstants.FINISH_TASK_EVENT);
    }

    boolean changeTaskStateAndRecordEvent(String taskId, String targetStatus, String eventType) {
        if (StringUtils.isEmpty(taskId) || StringUtils.isEmpty(targetStatus) || StringUtils.isEmpty(eventType)) {
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
        return save(taskEvent);
    }

    protected boolean updateTaskStatus(String taskId, String targetStatus) {
        return Db.lambdaUpdate(OperationTask.class)
                .set(OperationTask::getStatus, targetStatus)
                .eq(OperationTask::getTaskId, taskId)
                .update();
    }

    protected String loadMachineIdByTaskId(String taskId) {
        TaskAssignment assignment = Db.lambdaQuery(TaskAssignment.class)
                .select(TaskAssignment::getMachineId)
                .eq(TaskAssignment::getTaskId, taskId)
                .last("limit 1")
                .one();
        return assignment == null ? null : assignment.getMachineId();
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

    private String buildBizId(Object entity) {
        BizIdPrefix annotation = entity.getClass().getAnnotation(BizIdPrefix.class);
        String prefix = annotation != null ? annotation.value() : null;
        String suffix = IdUtils.simpleUUID();
        return StringUtils.isNotEmpty(prefix) ? prefix + suffix : suffix;
    }
}
