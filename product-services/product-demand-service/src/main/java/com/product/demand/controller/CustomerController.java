package com.product.demand.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.demand.common.core.page.TableDataInfo;
import com.product.demand.common.core.result.AjaxResult;
import com.product.demand.common.utils.PageUtils;
import com.product.demand.common.utils.ServletUtils;
import com.product.demand.common.utils.StringUtils;
import com.product.demand.core.controller.BaseController;
import com.product.demand.core.utils.ExcelUtil;
import com.product.demand.domain.entity.Customer;
import com.product.demand.service.ICustomerService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

import static com.product.demand.common.core.page.TableSupport.PAGE_NUM;
import static com.product.demand.common.core.page.TableSupport.PAGE_SIZE;

/**
 * 客户Controller（单体 product-demand CustomerController 移植）
 *
 * @author product
 * @date 2025-12-23
 */
@RestController
@RequestMapping("/demand/customer")
public class CustomerController extends BaseController {
    @Autowired
    private ICustomerService customerService;

    /**
     * 查询客户列表
     */
    @GetMapping("/list")
    public TableDataInfo list(Customer customer) {
        if (!StringUtils.hasText(ServletUtils.getParameter(PAGE_NUM)) || !StringUtils.hasText(ServletUtils.getParameter(PAGE_SIZE))) {
            return getDataTable(customerService.selectCustomerPage());
        }
        Page<Customer> page = PageUtils.buildPage();
        return getDataTable(customerService.selectCustomerPage(page, customer));
    }

    /**
     * 导出客户列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, Customer customer) {
        List<Customer> list = customerService.selectCustomerList(customer);
        ExcelUtil<Customer> util = new ExcelUtil<Customer>(Customer.class);
        util.exportExcel(response, list, "客户数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<Customer> util = new ExcelUtil<Customer>(Customer.class);
        util.importTemplateExcel(response, "客户数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<Customer> util = new ExcelUtil<Customer>(Customer.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<Customer> list = util.importExcel(inputStream);
            int count = customerService.batchInsertCustomer(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取客户详细信息
     */
    @GetMapping(value = "/{customerId}")
    public AjaxResult getInfo(@PathVariable("customerId") Long customerId) {
        return success(customerService.selectCustomerByCustomerId(customerId));
    }

    /**
     * 新增客户
     */
    @PostMapping
    public AjaxResult add(@RequestBody Customer customer) {
        return toAjax(customerService.insertCustomer(customer));
    }

    /**
     * 修改客户
     */
    @PutMapping
    public AjaxResult edit(@RequestBody Customer customer) {
        return toAjax(customerService.updateCustomer(customer));
    }

    /**
     * 删除客户
     */
    @DeleteMapping("/{customerIds}")
    public AjaxResult remove(@PathVariable Long[] customerIds) {
        return toAjax(customerService.deleteCustomerByCustomerIds(customerIds));
    }
}
