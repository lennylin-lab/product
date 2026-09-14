package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.masterdata.core.controller.BaseController;
import com.product.masterdata.common.core.page.TableDataInfo;
import com.product.masterdata.common.core.result.AjaxResult;
import com.product.masterdata.core.utils.ExcelUtil;
import com.product.masterdata.common.utils.PageUtils;
import com.product.masterdata.domain.entity.Calendar;
import com.product.masterdata.service.ICalendarService;
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
 * 班次/日历Controller
 *
 * @author product
 * @date 2026-01-01
 */
@RestController
@RequestMapping("/master/calendar")
public class CalendarController extends BaseController {
    @Autowired
    private ICalendarService calendarService;

    /**
     * 查询班次/日历列表
     */
    @GetMapping("/list")
    public TableDataInfo list(Calendar calendar) {
        Page<Calendar> page = PageUtils.buildPage();
        return getDataTable(calendarService.selectCalendarPage(page, calendar));
    }

    /**
     * 导出班次/日历列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, Calendar calendar) {
        List<Calendar> list = calendarService.selectCalendarList(calendar);
        ExcelUtil<Calendar> util = new ExcelUtil<Calendar>(Calendar.class);
        util.exportExcel(response, list, "班次/日历数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<Calendar> util = new ExcelUtil<Calendar>(Calendar.class);
        util.importTemplateExcel(response, "班次/日历数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<Calendar> util = new ExcelUtil<Calendar>(Calendar.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<Calendar> list = util.importExcel(inputStream);
            int count = calendarService.batchInsertCalendar(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取班次/日历详细信息
     */
    @GetMapping(value = "/{calendarId}")
    public AjaxResult getInfo(@PathVariable("calendarId") Long calendarId) {
        return success(calendarService.selectCalendarByCalendarId(calendarId));
    }

    /**
     * 新增班次/日历
     */
    @PostMapping
    public AjaxResult add(@RequestBody Calendar calendar) {
        return toAjax(calendarService.insertCalendar(calendar));
    }

    /**
     * 修改班次/日历
     */
    @PutMapping
    public AjaxResult edit(@RequestBody Calendar calendar) {
        return toAjax(calendarService.updateCalendar(calendar));
    }

    /**
     * 删除班次/日历
     */
    @DeleteMapping("/{calendarIds}")
    public AjaxResult remove(@PathVariable String[] calendarIds) {
        return toAjax(calendarService.deleteCalendarByCalendarIds(calendarIds));
    }
}
