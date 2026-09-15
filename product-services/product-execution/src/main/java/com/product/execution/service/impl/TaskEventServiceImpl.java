package com.product.execution.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.execution.common.constant.TaskEventConstants;
import com.product.execution.common.exception.ServiceException;
import com.product.execution.common.utils.StringUtils;
import com.product.execution.domain.entity.TaskEvent;
import com.product.execution.mapper.TaskEventMapper;
import com.product.execution.service.ITaskEventService;
import com.product.planning.api.PlanningTaskApi;
import com.product.planning.api.dto.PlanningContracts;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务事件日志（全流程追溯核心）Service业务层处理（MyBatis-Plus）
 * （单体 product-execute TaskEventServiceImpl 忠实移植 + Phase 5 事件链改造）。
 *
 * <p>改造点（差异已归类，详见任务 implement.md Phase 5 记录）：</p>
 * <ul>
 *   <li>单体在命令内同步 {@code update operation_task}（跨模块写 planning 表）并级联
 *       刷新批次/订单行/订单状态；事件链改为：命令内仅写 task_event + outbox
 *       （同一本地事务），任务/批次状态由 product-planning 消费 task.status.changed
 *       推进，订单行/订单由 product-demand 消费 batch.progress.changed 推进
 *       （ADR-0002 决策 5 / ADR-0004，跨域不再同步调用）。</li>
 *   <li>任务存在性与派工机台改经 planning 只读契约 {@link PlanningTaskApi}（单体为
 *       同库进程内查询 task_assignment/operation_task）：任务不存在 → 命令失败返回
 *       false（与单体 update 影响 0 行同语义）；planning 不可达 → fail-closed 返回
 *       false（与单体同库不可达即失败等价）。resource_id 填派工主行机台
 *       （单体 loadMachineIdByTaskId 同源语义，无派工为 null）。</li>
 *   <li>与单体行为一致的语义冻结点：目标状态无条件映射（START/RESUME→RUNNING、
 *       PAUSE→PAUSED、FINISH→DONE，无前置状态守卫）；taskId 为空 → false；
 *       CRUD（add/importData）只写事件行、不触发任何状态联动（单体如此）；
 *       命令成功返回 true（单体成功路径返回值一致）。</li>
 * </ul>
 */
@Slf4j
@Service
public class TaskEventServiceImpl extends ServiceImpl<TaskEventMapper, TaskEvent> implements ITaskEventService {

    @Autowired
    private PlanningTaskApi planningTaskApi;

    @Autowired
    private OutboxPublisher outboxPublisher;

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
     * 新增任务事件日志（全流程追溯核心）。
     *
     * <p>issue #4 决议：REST 直录路径与内部命令链 record() 语义对齐——
     * taskId 非法（空/非正数）拒绝写入；任务存在性经 planning 只读契约校验
     * （fail-closed，契约不可达同样拒绝，防止事件挂在不存在任务上）；
     * eventTime 缺省时由服务端补当前时间。</p>
     */
    @Override
    public boolean insertTaskEvent(TaskEvent taskEvent) {
        if (taskEvent == null || taskEvent.getTaskId() == null || taskEvent.getTaskId() <= 0) {
            throw new ServiceException("任务事件必须指定有效的任务ID");
        }
        if (loadTaskRuntime(taskEvent.getTaskId()) == null) {
            throw new ServiceException("任务不存在: taskId=" + taskEvent.getTaskId());
        }
        if (taskEvent.getEventTime() == null) {
            taskEvent.setEventTime(LocalDateTime.now());
        }
        if (taskEvent.getEventId() == null) {
            taskEvent.setEventId(IdWorker.getId());
        }
        return save(taskEvent);
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
     * @param eventId 任务事件日志（全流程追溯核心）主键
     * @return 是否成功
     */
    @Override
    public boolean deleteTaskEventByEventId(Long eventId) {
        return removeById(eventId);
    }

    /** 开始任务：记录开始事件并发布 task.status.changed（目标 RUNNING） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean start(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                com.product.execution.common.constant.StatusConstants.RUNNING_OPERATION_TASK,
                TaskEventConstants.START_TASK_EVENT);
    }

    /** 暂停任务：记录暂停事件并发布 task.status.changed（目标 PAUSED） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pause(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                com.product.execution.common.constant.StatusConstants.PAUSED_OPERATION_TASK,
                TaskEventConstants.PAUSE_TASK_EVENT);
    }

    /** 恢复任务：记录恢复事件并发布 task.status.changed（目标 RUNNING） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean resume(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                com.product.execution.common.constant.StatusConstants.RUNNING_OPERATION_TASK,
                TaskEventConstants.RESUME_TASK_EVENT);
    }

    /** 完成任务：记录完成事件并发布 task.status.changed（目标 DONE） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long taskId) {
        return changeTaskStateAndRecordEvent(taskId,
                com.product.execution.common.constant.StatusConstants.DONE_OPERATION_TASK,
                TaskEventConstants.FINISH_TASK_EVENT);
    }

    /**
     * 任务状态变更的核心流程（Phase 5 事件链）：契约校验任务 → 记录事件日志 →
     * outbox 发布 task.status.changed（与事件行同一本地事务，ADR-0004 §3）。
     *
     * @return 全部成功返回 true；任务为空/不存在/契约不可达/事件保存失败返回 false
     */
    boolean changeTaskStateAndRecordEvent(Long taskId, String targetStatus, String eventType) {
        if (taskId == null || StringUtils.isEmpty(targetStatus) || StringUtils.isEmpty(eventType)) {
            return false;
        }
        PlanningContracts.TaskRuntimeDTO runtime = loadTaskRuntime(taskId);
        if (runtime == null) {
            // 单体语义：update operation_task 影响 0 行（任务不存在）→ 命令失败
            return false;
        }
        TaskEvent taskEvent = new TaskEvent();
        taskEvent.setTaskId(taskId);
        taskEvent.setEventType(eventType);
        taskEvent.setEventTime(LocalDateTime.now());
        taskEvent.setResourceId(loadMachineIdByTaskId(runtime));
        boolean saved = save(taskEvent);
        if (!saved) {
            return false;
        }
        publishTaskStatusChanged(taskId, eventType, targetStatus, runtime, taskEvent.getEventId());
        return true;
    }

    /**
     * 经 planning 只读契约校验任务存在性（单体进程内 update 前置存在的等价物）。
     * 契约不可达/超时/响应非法 → fail-closed 返回 null（命令失败，无静默放行）。
     */
    protected PlanningContracts.TaskRuntimeDTO loadTaskRuntime(Long taskId) {
        try {
            PlanningContracts.TaskRuntimeResponse response = planningTaskApi.taskRuntimes(
                    new PlanningContracts.TaskRuntimeRequest(List.of(taskId)));
            if (response == null || CollectionUtils.isEmpty(response.getRuntimes())) {
                return null;
            }
            return response.getRuntimes().stream()
                    .filter(runtime -> taskId.equals(runtime.getTaskId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("任务运行时契约不可达，任务事件命令 fail-closed: taskId={} err={}", taskId, e.getMessage());
            return null;
        }
    }

    /** 事件关联资源 = 派工主行机台（单体 loadMachineIdByTaskId 同源语义；无派工为 null）。 */
    protected Long loadMachineIdByTaskId(PlanningContracts.TaskRuntimeDTO runtime) {
        return runtime == null ? null : runtime.getMachineId();
    }

    /**
     * 发布 task.status.changed（Execution → Planning；payload 见 EventTypes，
     * 只包含本域已提交数据 + planning 契约只读字段）。
     */
    protected void publishTaskStatusChanged(Long taskId, String eventType, String targetStatus,
                                            PlanningContracts.TaskRuntimeDTO runtime, Long occurredEventId) {
        EventEnvelope envelope = buildTaskStatusEnvelope(taskId, eventType, targetStatus,
                loadMachineIdByTaskId(runtime), occurredEventId);
        outboxPublisher.append(envelope);
        log.info("task.status.changed 已入发件箱: taskId={} eventType={} targetStatus={} eventId={}",
                taskId, eventType, targetStatus, envelope.getEventId());
    }

    /** 组装 task.status.changed 信封（纯函数，便于契约单测；payload 字段见 EventTypes）。 */
    protected EventEnvelope buildTaskStatusEnvelope(Long taskId, String eventType, String targetStatus,
                                                    Long machineId, Long occurredEventId) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventType(EventTypes.TASK_STATUS_CHANGED);
        envelope.setAggregateId(String.valueOf(taskId));
        envelope.setCorrelationId(currentTraceId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("eventType", eventType);
        payload.put("targetStatus", targetStatus);
        payload.put("resourceId", machineId);
        payload.put("occurredEventId", occurredEventId);
        envelope.setPayload(payload);
        envelope.setOccurredAt(EnvelopeCodec.now());
        return envelope;
    }

    /** correlationId 透传触发命令的 traceId（RequestContextFilter 注入 MDC）；无上下文时取随机值。 */
    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    /**
     * 构建查询条件（单体实现原样移植）
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
