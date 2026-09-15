package com.product.execution.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.execution.domain.entity.TaskEvent;

import java.util.List;

/**
 * 任务事件日志（全流程追溯核心）Service接口（单体 product-execute ITaskEventService 移植；
 * 状态联动方法语义冻结：开始/暂停/恢复/完成仅记录事件 + 发布事件，任务/批次/订单状态
 * 由 Planning/Demand 消费事件推进，Phase 5 事件链）。
 */
public interface ITaskEventService {

    /**
     * 查询任务事件日志（全流程追溯核心）
     *
     * @param eventId 任务事件日志（全流程追溯核心）主键
     * @return 任务事件日志（全流程追溯核心）
     */
    TaskEvent selectTaskEventByEventId(Long eventId);

    /**
     * 查询任务事件日志（全流程追溯核心）列表
     *
     * @param taskEvent 查询条件
     * @return 任务事件日志（全流程追溯核心）集合
     */
    List<TaskEvent> selectTaskEventList(TaskEvent taskEvent);

    /**
     * 分页查询任务事件日志（全流程追溯核心）列表
     *
     * @param page      分页参数
     * @param taskEvent 查询条件
     * @return 分页结果
     */
    Page<TaskEvent> selectTaskEventPage(Page<TaskEvent> page, TaskEvent taskEvent);

    /**
     * 新增任务事件日志（全流程追溯核心）
     *
     * @param taskEvent 任务事件日志（全流程追溯核心）
     * @return 是否成功
     */
    boolean insertTaskEvent(TaskEvent taskEvent);

    /**
     * 批量新增任务事件日志（全流程追溯核心）
     *
     * @param taskEvents 任务事件日志（全流程追溯核心）列表
     * @return 成功条数
     */
    int batchInsertTaskEvent(List<TaskEvent> taskEvents);

    /**
     * 修改任务事件日志（全流程追溯核心）
     *
     * @param taskEvent 任务事件日志（全流程追溯核心）
     * @return 是否成功
     */
    boolean updateTaskEvent(TaskEvent taskEvent);

    /**
     * 批量删除任务事件日志（全流程追溯核心）
     *
     * @param eventIds 需要删除的任务事件日志（全流程追溯核心）主键集合
     * @return 结果
     */
    boolean deleteTaskEventByEventIds(String[] eventIds);

    /**
     * 删除任务事件日志（全流程追溯核心）信息
     *
     * @param eventId 任务事件日志（全流程追溯核心）主键
     * @return 结果
     */
    boolean deleteTaskEventByEventId(Long eventId);

    /** 开始任务：记录开始事件并发布 task.status.changed（目标 RUNNING） */
    boolean start(Long taskId);

    /** 暂停任务：记录暂停事件并发布 task.status.changed（目标 PAUSED） */
    boolean pause(Long taskId);

    /** 恢复任务：记录恢复事件并发布 task.status.changed（目标 RUNNING） */
    boolean resume(Long taskId);

    /** 完成任务：记录完成事件并发布 task.status.changed（目标 DONE） */
    boolean complete(Long taskId);

    /**
     * 异常上报（KD1 增量）：reasonCode 必填（缺失拒绝）、remark 可选；记录异常事件
     * （事件行落 reason_code/remark）并发布 task.status.changed（目标 PAUSED，
     * 与 PAUSE 同款目标态——消费侧复用既有暂停级联，零新逻辑）。
     */
    boolean exception(Long taskId, String reasonCode, String remark);
}
