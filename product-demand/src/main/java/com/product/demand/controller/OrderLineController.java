package com.product.demand.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.core.controller.BaseController;
import com.product.common.core.page.TableDataInfo;
import com.product.common.core.result.AjaxResult;
import com.product.core.utils.ExcelUtil;
import com.product.common.utils.PageUtils;
import com.product.domain.entity.OrderLine;
import com.product.demand.service.IOrderLineService;
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
 * 订单明细Controller
 *
 * @author product
 * @date 2025-12-27
 */
@RestController
@RequestMapping("/demand/orderLine")
public class OrderLineController extends BaseController {
    @Autowired
    private IOrderLineService orderLineService;

    /**
     * 查询订单明细列表
     */
    @GetMapping("/list")
    public TableDataInfo list(OrderLine orderLine) {
        Page<OrderLine> page = PageUtils.buildPage();
        return getDataTable(orderLineService.selectOrderLinePage(page, orderLine));
    }

    /**
     * 导出订单明细列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, OrderLine orderLine) {
        List<OrderLine> list = orderLineService.selectOrderLineList(orderLine);
        ExcelUtil<OrderLine> util = new ExcelUtil<OrderLine>(OrderLine.class);
        util.exportExcel(response, list, "订单明细数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<OrderLine> util = new ExcelUtil<OrderLine>(OrderLine.class);
        util.importTemplateExcel(response, "订单明细数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<OrderLine> util = new ExcelUtil<OrderLine>(OrderLine.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<OrderLine> list = util.importExcel(inputStream);
            int count = orderLineService.batchInsertOrderLine(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取订单明细详细信息
     */
    @GetMapping(value = "/{orderLineId}")
    public AjaxResult getInfo(@PathVariable("orderLineId") Long orderLineId) {
        return success(orderLineService.selectOrderLineByOrderLineId(orderLineId));
    }

    /**
     * 新增订单明细
     */
    @PostMapping
    public AjaxResult add(@RequestBody OrderLine orderLine) {
        return toAjax(orderLineService.insertOrderLine(orderLine));
    }

    /**
     * 修改订单明细
     */
    @PutMapping
    public AjaxResult edit(@RequestBody OrderLine orderLine) {
        return toAjax(orderLineService.updateOrderLine(orderLine));
    }

    /**
     * 删除订单明细
     */
    @DeleteMapping("/{orderLineIds}")
    public AjaxResult remove(@PathVariable String[] orderLineIds) {
        return toAjax(orderLineService.deleteOrderLineByOrderLineIds(orderLineIds));
    }

    /**
     * 释放订单行
     */
    @PutMapping("/release/{orderLineId}")
    public AjaxResult release(@PathVariable("orderLineId") Long orderLineId) {
        return toAjax(orderLineService.release(orderLineId));
    }

    /**
     * 取消释放订单行
     */
    @PutMapping("/cancelRelease/{orderLineId}")
    public AjaxResult cancelRelease(@PathVariable("orderLineId") Long orderLineId) {
        return toAjax(orderLineService.cancelRelease(orderLineId));
    }
}
