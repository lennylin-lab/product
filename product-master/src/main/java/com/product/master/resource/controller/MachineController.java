package com.product.master.resource.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.common.core.page.TableDataInfo;
import com.product.common.core.result.AjaxResult;
import com.product.common.utils.PageUtils;
import com.product.core.controller.BaseController;
import com.product.core.utils.ExcelUtil;
import com.product.domain.dto.MachineResource;
import com.product.domain.vo.MachineResourceVO;
import com.product.domain.entity.Machine;
import com.product.master.resource.service.IMachineService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 注塑机扩展信息Controller
 *
 * @author product
 * @date 2026-01-01
 */
@RestController
@RequestMapping("/master/resource/machine")
public class MachineController extends BaseController {
    @Autowired
    private IMachineService machineService;

    /**
     * 查询注塑机扩展信息列表
     */
    @GetMapping("/list")
    public TableDataInfo list(MachineResource machineResource) {
        Page<MachineResourceVO> page = PageUtils.buildPage();
        return getDataTable(machineService.selectMachinePage(page, machineResource));
    }

    /**
     * 导出注塑机扩展信息列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, Machine machine) {
        List<Machine> list = machineService.selectMachineList(machine);
        ExcelUtil<Machine> util = new ExcelUtil<Machine>(Machine.class);
        util.exportExcel(response, list, "注塑机扩展信息数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<Machine> util = new ExcelUtil<Machine>(Machine.class);
        util.importTemplateExcel(response, "注塑机扩展信息数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<Machine> util = new ExcelUtil<Machine>(Machine.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<Machine> list = util.importExcel(inputStream);
            int count = machineService.batchInsertMachine(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取注塑机扩展信息详细信息
     */
    @GetMapping(value = "/{machineId}")
    public AjaxResult getInfo(@PathVariable("machineId") String machineId) {
        return success(machineService.selectMachineByMachineId(machineId));
    }

    /**
     * 新增注塑机扩展信息
     */
    @PostMapping
    public AjaxResult add(@RequestBody MachineResource machineResource) {
        return toAjax(machineService.insertMachine(machineResource));
    }

    /**
     * 修改注塑机扩展信息
     */
    @PutMapping
    public AjaxResult edit(@RequestBody MachineResource machineResource) {
        return toAjax(machineService.updateMachine(machineResource));
    }

    /**
     * 删除注塑机扩展信息
     */
    @DeleteMapping("/{machineIds}")
    public AjaxResult remove(@PathVariable String[] machineIds) {
        return toAjax(machineService.deleteMachineByMachineIds(machineIds));
    }

    /**
     * 故障
     */
    @PutMapping("/down/{machineId}")
    public AjaxResult down(@PathVariable("machineId") String machineId) {
        return toAjax(machineService.down(machineId));
    }

    /**
     * 保养
     */
    @PutMapping("/maintenance/{machineId}")
    public AjaxResult maintenance(@PathVariable("machineId") String machineId) {
        return toAjax(machineService.maintenance(machineId));
    }

    /**
     * 恢复
     */
    @PutMapping("/restore/{machineId}")
    public AjaxResult restore(@PathVariable("machineId") String machineId) {
        return toAjax(machineService.restore(machineId));
    }
}
