package com.product.demand.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.demand.common.core.page.TableDataInfo;
import com.product.demand.common.core.result.AjaxResult;
import com.product.demand.common.utils.PageUtils;
import com.product.demand.core.controller.BaseController;
import com.product.demand.core.utils.ExcelUtil;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.vo.CustomerOrderVO;
import com.product.demand.service.ICustomerOrderService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 订单Controller（单体 product-demand CustomerOrderController 移植）
 *
 * @author product
 * @date 2025-12-26
 */
@RestController
@RequestMapping("/demand/order")
public class CustomerOrderController extends BaseController {
    @Autowired
    private ICustomerOrderService customerOrderService;

    /**
     * 查询订单列表
     */
    @GetMapping("/list")
    public TableDataInfo list(ServletRequest request, CustomerOrder customerOrder) {
        Page<CustomerOrderVO> page = PageUtils.buildPage();
        return getDataTable(customerOrderService.selectCustomerOrderPage(page, customerOrder));
    }

    /**
     * 导出订单列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, CustomerOrder customerOrder) {
        List<CustomerOrder> list = customerOrderService.selectCustomerOrderList(customerOrder);
        ExcelUtil<CustomerOrder> util = new ExcelUtil<CustomerOrder>(CustomerOrder.class);
        util.exportExcel(response, list, "订单数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<CustomerOrder> util = new ExcelUtil<CustomerOrder>(CustomerOrder.class);
        util.importTemplateExcel(response, "订单数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<CustomerOrder> util = new ExcelUtil<CustomerOrder>(CustomerOrder.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<CustomerOrder> list = util.importExcel(inputStream);
            int count = customerOrderService.batchInsertCustomerOrder(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取订单详细信息
     */
    @GetMapping(value = "/{orderId}")
    public AjaxResult getInfo(@PathVariable("orderId") Long orderId) {
        return success(customerOrderService.selectCustomerOrderByOrderId(orderId));
    }

    /**
     * 新增订单
     */
    @PostMapping
    public AjaxResult add(@RequestBody CustomerOrder customerOrder) {
        return toAjax(customerOrderService.insertCustomerOrder(customerOrder));
    }

    /**
     * 修改订单
     */
    @PutMapping
    public AjaxResult edit(@RequestBody CustomerOrder customerOrder) {
        return toAjax(customerOrderService.updateCustomerOrder(customerOrder));
    }

    /**
     * 删除订单
     */
    @DeleteMapping("/{orderIds}")
    public AjaxResult remove(@PathVariable String[] orderIds) {
        return toAjax(customerOrderService.deleteCustomerOrderByOrderIds(orderIds));
    }

    /**
     * 确认订单
     */
    @PutMapping("/check/{orderId}")
    public AjaxResult check(@PathVariable("orderId") Long orderId) {
        return toAjax(customerOrderService.check(orderId));
    }

    /**
     * 取消确认订单
     */
    @PutMapping("/cancelCheck/{orderId}")
    public AjaxResult cancelCheck(@PathVariable("orderId") Long orderId) {
        return toAjax(customerOrderService.cancelCheck(orderId));
    }
}
