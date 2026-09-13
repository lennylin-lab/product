package com.product.execute.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.core.controller.BaseController;
import com.product.common.core.page.TableDataInfo;
import com.product.common.core.result.AjaxResult;
import com.product.core.utils.ExcelUtil;
import com.product.common.utils.PageUtils;
import com.product.domain.dto.PauseTaskDTO;
import com.product.domain.entity.TaskEvent;
import com.product.execute.service.ITaskEventService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 任务事件日志（全流程追溯核心）Controller
 *
 * @author product
 * @date 2026-01-03
 */
@RestController
@RequestMapping("/execute/event")
public class TaskEventController extends BaseController {
    @Autowired
    private ITaskEventService taskEventService;

    /**
     * 查询任务事件日志（全流程追溯核心）列表
     */
    @GetMapping("/list")
    public TableDataInfo list(TaskEvent taskEvent) {
        Page<TaskEvent> page = PageUtils.buildPage();
        return getDataTable(taskEventService.selectTaskEventPage(page, taskEvent));
    }

    /**
     * 导出任务事件日志（全流程追溯核心）列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, TaskEvent taskEvent) {
        List<TaskEvent> list = taskEventService.selectTaskEventList(taskEvent);
        ExcelUtil<TaskEvent> util = new ExcelUtil<TaskEvent>(TaskEvent.class);
        util.exportExcel(response, list, "任务事件日志（全流程追溯核心）数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<TaskEvent> util = new ExcelUtil<TaskEvent>(TaskEvent.class);
        util.importTemplateExcel(response, "任务事件日志（全流程追溯核心）数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<TaskEvent> util = new ExcelUtil<TaskEvent>(TaskEvent.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<TaskEvent> list = util.importExcel(inputStream);
            int count = taskEventService.batchInsertTaskEvent(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取任务事件日志（全流程追溯核心）详细信息
     */
    @GetMapping(value = "/{eventId}")
    public AjaxResult getInfo(@PathVariable("eventId") Long eventId) {
        return success(taskEventService.selectTaskEventByEventId(eventId));
    }

    /**
     * 新增任务事件日志（全流程追溯核心）
     */
    @PostMapping
    public AjaxResult add(@RequestBody TaskEvent taskEvent) {
        return toAjax(taskEventService.insertTaskEvent(taskEvent));
    }

    /**
     * 修改任务事件日志（全流程追溯核心）
     */
    @PutMapping
    public AjaxResult edit(@RequestBody TaskEvent taskEvent) {
        return toAjax(taskEventService.updateTaskEvent(taskEvent));
    }

    /**
     * 删除任务事件日志（全流程追溯核心）
     */
    @DeleteMapping("/{eventIds}")
    public AjaxResult remove(@PathVariable String[] eventIds) {
        return toAjax(taskEventService.deleteTaskEventByEventIds(eventIds));
    }

    /**
     * 开工
     */
    @PostMapping("/start/{taskId}")
    public AjaxResult start(@PathVariable Long taskId) {
        return toAjax(taskEventService.start(taskId));
    }

    /**
     * 暂停
     */
    @PostMapping("/pause/{taskId}")
    public AjaxResult pause(@PathVariable Long taskId, @RequestBody PauseTaskDTO pauseTaskDTO) {
        return toAjax(taskEventService.pause(taskId));
    }

    /**
     * 恢复
     */
    @PostMapping("/resume/{taskId}")
    public AjaxResult resume(@PathVariable Long taskId) {
        return toAjax(taskEventService.resume(taskId));
    }

    /**
     * 完工
     */
    @PostMapping("/complete/{taskId}")
    public AjaxResult complete(@PathVariable Long taskId) {
        return toAjax(taskEventService.complete(taskId));
    }
}
