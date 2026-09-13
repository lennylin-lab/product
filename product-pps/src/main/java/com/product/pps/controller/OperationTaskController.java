package com.product.pps.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.core.controller.BaseController;
import com.product.common.core.page.TableDataInfo;
import com.product.common.core.result.AjaxResult;
import com.product.core.utils.ExcelUtil;
import com.product.common.utils.PageUtils;
import com.product.domain.entity.OperationTask;
import com.product.pps.service.IOperationTaskService;
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
 * 工序任务Controller
 *
 * @author product
 * @date 2025-12-31
 */
@RestController
@RequestMapping("/pps/task")
public class OperationTaskController extends BaseController {
    @Autowired
    private IOperationTaskService operationTaskService;

    /**
     * 查询工序任务列表
     */
    @GetMapping("/list")
    public TableDataInfo list(OperationTask operationTask) {
        Page<OperationTask> page = PageUtils.buildPage();
        return getDataTable(operationTaskService.selectOperationTaskPage(page, operationTask));
    }

    @GetMapping("/listReadyAndScheduled")
    public TableDataInfo listReadyAndScheduled(OperationTask operationTask) {
        Page<OperationTask> page = PageUtils.buildPage();
        return getDataTable(operationTaskService.selectReadyAndScheduledPage(page, operationTask));
    }

    /**
     * 导出工序任务列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, OperationTask operationTask) {
        List<OperationTask> list = operationTaskService.selectOperationTaskList(operationTask);
        ExcelUtil<OperationTask> util = new ExcelUtil<OperationTask>(OperationTask.class);
        util.exportExcel(response, list, "工序任务数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<OperationTask> util = new ExcelUtil<OperationTask>(OperationTask.class);
        util.importTemplateExcel(response, "工序任务数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<OperationTask> util = new ExcelUtil<OperationTask>(OperationTask.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<OperationTask> list = util.importExcel(inputStream);
            int count = operationTaskService.batchInsertOperationTask(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取工序任务详细信息
     */
    @GetMapping(value = "/{taskId}")
    public AjaxResult getInfo(@PathVariable("taskId") Long taskId) {
        return success(operationTaskService.selectOperationTaskByTaskId(taskId));
    }

    /**
     * 新增工序任务
     */
    @PostMapping
    public AjaxResult add(@RequestBody OperationTask operationTask) {
        return toAjax(operationTaskService.insertOperationTask(operationTask));
    }

    /**
     * 修改工序任务
     */
    @PutMapping
    public AjaxResult edit(@RequestBody OperationTask operationTask) {
        return toAjax(operationTaskService.updateOperationTask(operationTask));
    }

    /**
     * 删除工序任务
     */
    @DeleteMapping("/{taskIds}")
    public AjaxResult remove(@PathVariable String[] taskIds) {
        return toAjax(operationTaskService.deleteOperationTaskByTaskIds(taskIds));
    }

    /**
     * 取消任务
     */
    @PutMapping("/cancel/{taskId}")
    public AjaxResult cancel(@PathVariable("taskId") Long taskId) {
        return toAjax(operationTaskService.cancel(taskId));
    }

    /**
     * 恢复任务
     */
    @PutMapping("/restore/{taskId}")
    public AjaxResult restore(@PathVariable("taskId") Long taskId) {
        return toAjax(operationTaskService.restore(taskId));
    }

    /**
     * 撤销排程
     */
    @PutMapping("/revokeSchedule/{taskId}")
    public AjaxResult revokeSchedule(@PathVariable("taskId") Long taskId) {
        return toAjax(operationTaskService.revokeSchedule(taskId));
    }
}
