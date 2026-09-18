package com.product.planning.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.planning.core.controller.BaseController;
import com.product.planning.common.core.page.TableDataInfo;
import com.product.planning.common.core.result.AjaxResult;
import com.product.planning.core.utils.ExcelUtil;
import com.product.planning.common.utils.PageUtils;
import com.product.planning.domain.dto.TaskAssignmentDTO;
import com.product.planning.domain.entity.ScheduleJob;
import com.product.planning.domain.entity.TaskAssignment;
import com.product.planning.domain.vo.TaskAssignmentVO;
import com.product.planning.service.ITaskAssignmentService;
import com.product.planning.service.impl.ScheduleJobTimeoutService;
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
 * 派工/排程结果Controller
 *
 * @author product
 * @date 2026-01-02
 */
@RestController
@RequestMapping("/pps/assignment")
public class TaskAssignmentController extends BaseController {
    @Autowired
    private ITaskAssignmentService taskAssignmentService;
    @Autowired
    private ScheduleJobTimeoutService scheduleJobTimeoutService;

    /**
     * 查询派工/排程结果列表
     */
    @GetMapping("/list")
    public TableDataInfo list(TaskAssignment taskAssignment) {
        Page<TaskAssignmentVO> page = PageUtils.buildPage();
        return getDataTable(taskAssignmentService.selectTaskAssignmentPage(page, taskAssignment));
    }

    /**
     * 导出派工/排程结果列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, TaskAssignment taskAssignment) {
        List<TaskAssignment> list = taskAssignmentService.selectTaskAssignmentList(taskAssignment);
        ExcelUtil<TaskAssignment> util = new ExcelUtil<TaskAssignment>(TaskAssignment.class);
        util.exportExcel(response, list, "派工/排程结果数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<TaskAssignment> util = new ExcelUtil<TaskAssignment>(TaskAssignment.class);
        util.importTemplateExcel(response, "派工/排程结果数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<TaskAssignment> util = new ExcelUtil<TaskAssignment>(TaskAssignment.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<TaskAssignment> list = util.importExcel(inputStream);
            int count = taskAssignmentService.batchInsertTaskAssignment(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取派工/排程结果详细信息
     */
    @GetMapping(value = "/{assignmentId:\\d+}")
    public AjaxResult getInfo(@PathVariable("assignmentId") Long assignmentId) {
        return success(taskAssignmentService.selectTaskAssignmentByAssignmentId(assignmentId));
    }

    /**
     * 新增派工/排程结果
     */
    @PostMapping
    public AjaxResult add(@RequestBody TaskAssignment taskAssignment) {
        return toAjax(taskAssignmentService.insertTaskAssignment(taskAssignment));
    }

    /**
     * 修改派工/排程结果
     */
    @PutMapping
    public AjaxResult edit(@RequestBody TaskAssignment taskAssignment) {
        return toAjax(taskAssignmentService.updateTaskAssignment(taskAssignment));
    }

    /**
     * 删除派工/排程结果
     */
    @DeleteMapping("/{assignmentIds}")
    public AjaxResult remove(@PathVariable Long[] assignmentIds) {
        return toAjax(taskAssignmentService.deleteTaskAssignmentByAssignmentIds(assignmentIds));
    }

    /**
     * 一键排程
     */
    @PostMapping("/schedule")
    public AjaxResult schedule(@RequestBody TaskAssignmentDTO taskAssignmentDTO) {
        return toAjax(taskAssignmentService.schedule(taskAssignmentDTO));
    }

    /**
     * 全部排程
     */
    @PostMapping("/scheduleAll")
    public AjaxResult scheduleAll(@RequestBody TaskAssignmentDTO taskAssignmentDTO) {
        return toAjax(taskAssignmentService.scheduleAll(taskAssignmentDTO));
    }

    /**
     * 异步全量排程
     * @param taskAssignmentDTO
     * @return
     */
    @PostMapping("/scheduleAllAsync")
    public AjaxResult scheduleAllAsync(@RequestBody TaskAssignmentDTO taskAssignmentDTO) {
        // 异步接口只返回 jobId，实际排程在后台线程执行。
        Long jobId = taskAssignmentService.scheduleAllAsync(taskAssignmentDTO);
        return AjaxResult.success("排程任务已提交", jobId);
    }

    @GetMapping("/scheduleJob/{jobId:\\d+}")
    public AjaxResult getScheduleJob(@PathVariable("jobId") Long jobId) {
        // 供前端轮询异步排程任务状态（PENDING/RUNNING/SUCCESS/FAILED）。
        ScheduleJob scheduleJob = taskAssignmentService.selectScheduleJobByJobId(jobId);
        return AjaxResult.success(scheduleJob);
    }

    @GetMapping("/scheduleJob/list")
    public TableDataInfo listScheduleJob(ScheduleJob scheduleJob) {
        Page<ScheduleJob> page = PageUtils.buildPage();
        return getDataTable(taskAssignmentService.selectScheduleJobPage(page, scheduleJob));
    }

    /**
     * 手动触发一次排程任务超时兜底扫描
     */
    @PostMapping("/scheduleJob/sweepTimeout")
    public AjaxResult sweepTimeoutScheduleJob() {
        int count = scheduleJobTimeoutService.sweepTimeoutJobsOnce();
        return AjaxResult.success("超时兜底扫描完成", count);
    }
}
