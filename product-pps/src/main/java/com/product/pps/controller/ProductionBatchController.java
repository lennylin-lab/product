package com.product.pps.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.common.core.page.TableDataInfo;
import com.product.common.core.result.AjaxResult;
import com.product.common.utils.PageUtils;
import com.product.core.controller.BaseController;
import com.product.core.utils.ExcelUtil;
import com.product.domain.dto.BatchSearchDTO;
import com.product.domain.entity.ProductionBatch;
import com.product.domain.vo.ProductionBatchVO;
import com.product.pps.service.IOperationTaskService;
import com.product.pps.service.IProductionBatchService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 生产批次（订单行拆批）Controller
 *
 * @author product
 * @date 2025-12-27
 */
@RestController
@RequestMapping("/pps/batch")
public class ProductionBatchController extends BaseController {
    @Autowired
    private IProductionBatchService productionBatchService;

    @Autowired
    private IOperationTaskService operationTaskService;
    /**
     * 查询生产批次（订单行拆批）列表
     */
    @GetMapping("/list")
    public TableDataInfo list(BatchSearchDTO batchSearchDTO) {
        Page<ProductionBatchVO> page = PageUtils.buildPage();
        return getDataTable(productionBatchService.selectProductionBatchPage(page, batchSearchDTO));
    }

    /**
     * 导出生产批次（订单行拆批）列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, ProductionBatch productionBatch) {
        List<ProductionBatch> list = productionBatchService.selectProductionBatchList(productionBatch);
        ExcelUtil<ProductionBatch> util = new ExcelUtil<ProductionBatch>(ProductionBatch.class);
        util.exportExcel(response, list, "生产批次（订单行拆批）数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<ProductionBatch> util = new ExcelUtil<ProductionBatch>(ProductionBatch.class);
        util.importTemplateExcel(response, "生产批次（订单行拆批）数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<ProductionBatch> util = new ExcelUtil<ProductionBatch>(ProductionBatch.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<ProductionBatch> list = util.importExcel(inputStream);
            int count = productionBatchService.batchInsertProductionBatch(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取生产批次（订单行拆批）详细信息
     */
    @GetMapping(value = "/{batchId}")
    public AjaxResult getInfo(@PathVariable("batchId") Long batchId) {
        return success(productionBatchService.selectProductionBatchByBatchId(batchId));
    }

    /**
     * 新增生产批次（订单行拆批）
     */
    @PostMapping
    public AjaxResult add(@RequestBody ProductionBatch productionBatch) {
        return toAjax(productionBatchService.insertProductionBatch(productionBatch));
    }

    /**
     * 修改生产批次（订单行拆批）
     */
    @PutMapping
    public AjaxResult edit(@RequestBody ProductionBatch productionBatch) {
        return toAjax(productionBatchService.updateProductionBatch(productionBatch));
    }

    /**
     * 删除生产批次（订单行拆批）
     */
    @DeleteMapping("/{batchIds}")
    public AjaxResult remove(@PathVariable Long[] batchIds) {
        return toAjax(productionBatchService.deleteProductionBatchByBatchIds(batchIds));
    }

    /**
     * 释放生产批次
     */
    @PutMapping("/release/{batchId}")
    public AjaxResult release(@PathVariable("batchId") Long batchId) {
        return toAjax(productionBatchService.release(batchId));
    }

    /**
     * 取消释放生产批次
     */
    @PutMapping("/cancelRelease/{batchId}")
    public AjaxResult cancelRelease(@PathVariable("batchId") Long batchId) {
        return toAjax(productionBatchService.cancelRelease(batchId));
    }

    /**
     * 生成生产任务
     */
    @PostMapping("/generateTask")
    public AjaxResult generateTask(@RequestBody List<Long> batchIds) {
        return operationTaskService.generateTask(batchIds);
    }

    /**
     * 重新生成生产任务
     */
    @PutMapping("/retryGenerateTask/{batchId}")
    public AjaxResult retryGenerateTask(@PathVariable Long batchId) {
        return operationTaskService.retryGenerateTask(batchId);
    }
}
