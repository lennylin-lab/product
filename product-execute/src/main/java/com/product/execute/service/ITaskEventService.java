package com.product.execute.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.domain.entity.TaskEvent;

import java.util.List;

/**
 * 任务事件日志（全流程追溯核心）Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-03
 */
public interface ITaskEventService extends IService<TaskEvent> {

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
     * @param eventIds 主键集合
     * @return 是否成功
     */
    boolean deleteTaskEventByEventIds(String[] eventIds);

    /**
     * 删除任务事件日志（全流程追溯核心）信息
     *
     * @param eventId 主键
     * @return 是否成功
     */
    boolean deleteTaskEventByEventId(Long eventId);

    boolean start(Long taskId);

    boolean pause(Long taskId);

    boolean resume(Long taskId);

    boolean complete(Long taskId);
}
