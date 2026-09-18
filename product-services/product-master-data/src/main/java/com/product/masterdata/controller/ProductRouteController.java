package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.masterdata.common.core.page.TableDataInfo;
import com.product.masterdata.common.core.result.AjaxResult;
import com.product.masterdata.common.utils.PageUtils;
import com.product.masterdata.core.controller.BaseController;
import com.product.masterdata.domain.entity.ProductRoute;
import com.product.masterdata.service.IProductRouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产品工艺路线 Controller（单体 product-pps ProductRouteController 移植；
 * 路线归 master-data 所有，对外路由保持 /pps/product-route——baselines.md §1.2）。
 */
@RestController
@RequestMapping("/pps/product-route")
public class ProductRouteController extends BaseController {

    @Autowired
    private IProductRouteService productRouteService;

    @GetMapping("/list")
    public TableDataInfo list(ProductRoute productRoute) {
        Page<ProductRoute> page = PageUtils.buildPage();
        return getDataTable(productRouteService.selectProductRoutePage(page, productRoute));
    }

    @GetMapping("/product/{productId:\\d+}")
    public AjaxResult listByProduct(@PathVariable Long productId) {
        List<ProductRoute> routes = productRouteService.listByProductId(productId);
        return success(routes);
    }

    @GetMapping("/product/{productId:\\d+}/active")
    public AjaxResult getActiveByProduct(@PathVariable Long productId) {
        return success(productRouteService.getActiveByProductId(productId));
    }

    @GetMapping("/{routeId:\\d+}")
    public AjaxResult getInfo(@PathVariable Long routeId) {
        return success(productRouteService.getByRouteId(routeId));
    }

    @PostMapping
    public AjaxResult add(@RequestBody ProductRoute productRoute) {
        Long routeId = productRouteService.createRoute(productRoute);
        AjaxResult result = success("操作成功");
        result.put("routeId", routeId);
        return result;
    }

    @PutMapping
    public AjaxResult edit(@RequestBody ProductRoute productRoute) {
        productRouteService.updateRoute(productRoute);
        return success();
    }

    @PutMapping("/{routeId:\\d+}/activate")
    public AjaxResult activate(@PathVariable Long routeId) {
        productRouteService.activateRoute(routeId);
        return success();
    }

    @DeleteMapping("/{routeId:\\d+}")
    public AjaxResult remove(@PathVariable Long routeId) {
        productRouteService.deleteRoute(routeId);
        return success();
    }
}
