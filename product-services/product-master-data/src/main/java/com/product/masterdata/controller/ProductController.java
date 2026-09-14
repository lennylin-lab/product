package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.masterdata.common.core.page.TableDataInfo;
import com.product.masterdata.common.core.result.AjaxResult;
import com.product.masterdata.common.utils.PageUtils;
import com.product.masterdata.common.utils.ServletUtils;
import com.product.masterdata.common.utils.StringUtils;
import com.product.masterdata.core.controller.BaseController;
import com.product.masterdata.core.utils.ExcelUtil;
import com.product.masterdata.domain.entity.Product;
import com.product.masterdata.service.IProductService;
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

import static com.product.masterdata.common.core.page.TableSupport.PAGE_NUM;
import static com.product.masterdata.common.core.page.TableSupport.PAGE_SIZE;

/**
 * 产品Controller（单体 product-demand ProductController 移植；产品归 master-data 所有，
 * 对外路由保持 /demand/product——baselines.md §1.2，Gateway 显式路由至此服务）。
 */
@RestController
@RequestMapping("/demand/product")
public class ProductController extends BaseController {
    @Autowired
    private IProductService productService;

    /**
     * 查询产品列表
     */
    @GetMapping("/list")
    public TableDataInfo list(Product product) {
        if (!StringUtils.hasText(ServletUtils.getParameter(PAGE_NUM)) || !StringUtils.hasText(ServletUtils.getParameter(PAGE_SIZE))) {
            return getDataTable(productService.selectCustomerPage());
        }
        Page<Product> page = PageUtils.buildPage();
        return getDataTable(productService.selectProductPage(page, product));
    }

    /**
     * 导出产品列表
     */
    @PostMapping("/export")
    public void export(HttpServletResponse response, Product product) {
        List<Product> list = productService.selectProductList(product);
        ExcelUtil<Product> util = new ExcelUtil<Product>(Product.class);
        util.exportExcel(response, list, "产品数据");
    }

    /**
     * 下载模板
     */
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<Product> util = new ExcelUtil<Product>(Product.class);
        util.importTemplateExcel(response, "产品数据");
    }

    /**
     * 导入数据
     */
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        ExcelUtil<Product> util = new ExcelUtil<Product>(Product.class);
        try (InputStream inputStream = file.getInputStream()) {
            List<Product> list = util.importExcel(inputStream);
            int count = productService.batchInsertProduct(list);
            return AjaxResult.success("导入成功" + count + "条信息！");
        }
    }

    /**
     * 获取产品详细信息
     */
    @GetMapping(value = "/{productId}")
    public AjaxResult getInfo(@PathVariable("productId") Long productId) {
        return success(productService.selectProductByProductId(productId));
    }

    /**
     * 新增产品
     */
    @PostMapping
    public AjaxResult add(@RequestBody Product product) {
        return toAjax(productService.insertProduct(product));
    }

    /**
     * 修改产品
     */
    @PutMapping
    public AjaxResult edit(@RequestBody Product product) {
        return toAjax(productService.updateProduct(product));
    }

    /**
     * 删除产品
     */
    @DeleteMapping("/{productIds}")
    public AjaxResult remove(@PathVariable Long[] productIds) {
        return toAjax(productService.deleteProductByProductIds(productIds));
    }
}
