package com.product.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.common.utils.StringUtils;
import com.product.masterdata.domain.entity.Product;
import com.product.masterdata.domain.entity.ProductMoldParam;
import com.product.masterdata.domain.validation.ProductMoldParamValidator;
import com.product.masterdata.mapper.ProductMapper;
import com.product.masterdata.service.IProductRouteService;
import com.product.masterdata.service.IProductService;
import com.product.masterdata.service.MasterDataVersionService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 产品Service（单体 product-demand ProductServiceImpl 移植）。
 *
 * <p>demand→pps Java 依赖消除点（Phase 3 目标 3）：单体中该服务位于 product-demand 模块、
 * 经进程内 {@code IProductRouteService} 维护产品启用路线；目标态产品与路线同属
 * master_data_db，本服务内直接注入本地 {@link IProductRouteService}，不再跨模块。
 * 对外路由保持 /demand/product（baselines.md §1.2）。</p>
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements IProductService {

    private final IProductRouteService productRouteService;

    private final MasterDataVersionService versionService;

    public ProductServiceImpl(IProductRouteService productRouteService, MasterDataVersionService versionService) {
        this.productRouteService = productRouteService;
        this.versionService = versionService;
    }

    @Override
    public Product selectProductByProductId(Long productId) {
        Product product = getById(productId);
        if (product == null) {
            return null;
        }
        product.setMoldParams(loadMoldParams(productId));
        product.setActiveRoute(productRouteService.getActiveByProductId(productId));
        return product;
    }

    @Override
    public List<Product> selectProductList(Product product) {
        return list(buildQueryWrapper(product));
    }

    @Override
    public Page<Product> selectProductPage(Page<Product> page, Product product) {
        return this.page(page, buildQueryWrapper(product));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertProduct(Product product) {
        ProductMoldParamValidator.requireMoldParamsForProduct(product.getMoldParams(), null);
        boolean saved = save(product);
        if (!saved) {
            throw new ServiceException("创建产品失败");
        }
        saveMoldParams(product.getProductId(), product.getMoldParams());
        if (product.getActiveRoute() != null) {
            productRouteService.saveActiveRouteForProduct(product.getProductId(), product.getActiveRoute());
        }
        versionService.bump();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchInsertProduct(List<Product> products) {
        if (CollectionUtils.isEmpty(products)) {
            return 0;
        }
        for (Product product : products) {
            insertProduct(product);
        }
        return products.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProduct(Product product) {
        if (product == null || product.getProductId() == null) {
            throw new ServiceException("产品ID不能为空");
        }
        if (CollectionUtils.isNotEmpty(product.getMoldParams())) {
            ProductMoldParamValidator.requireMoldParamsForProduct(product.getMoldParams(), product.getProductId());
        }
        boolean updated = updateById(product);
        if (!updated) {
            return false;
        }
        if (CollectionUtils.isNotEmpty(product.getMoldParams())) {
            replaceMoldParams(product.getProductId(), product.getMoldParams());
        }
        if (product.getActiveRoute() != null) {
            productRouteService.saveActiveRouteForProduct(product.getProductId(), product.getActiveRoute());
        }
        versionService.bump();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProductByProductIds(Long[] productIds) {
        if (productIds == null || productIds.length == 0) {
            return false;
        }
        Arrays.stream(productIds)
                .filter(id -> id != null)
                .forEach(this::deleteMoldParams);
        boolean removed = removeByIds(Arrays.asList(productIds));
        if (removed) {
            versionService.bump();
        }
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProductByProductId(Long productId) {
        deleteMoldParams(productId);
        boolean removed = removeById(productId);
        if (removed) {
            versionService.bump();
        }
        return removed;
    }

    private List<ProductMoldParam> loadMoldParams(Long productId) {
        return Db.lambdaQuery(ProductMoldParam.class)
                .eq(ProductMoldParam::getProductId, productId)
                .list();
    }

    private void saveMoldParams(Long productId, List<ProductMoldParam> moldParams) {
        for (ProductMoldParam param : moldParams) {
            param.setProductId(productId);
        }
        if (!Db.saveBatch(moldParams)) {
            throw new ServiceException("保存产品模具参数失败: productId=" + productId);
        }
    }

    private void replaceMoldParams(Long productId, List<ProductMoldParam> moldParams) {
        deleteMoldParams(productId);
        saveMoldParams(productId, moldParams);
    }

    private void deleteMoldParams(Long productId) {
        Db.lambdaUpdate(ProductMoldParam.class)
                .eq(ProductMoldParam::getProductId, productId)
                .remove();
    }

    private LambdaQueryWrapper<Product> buildQueryWrapper(Product product) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (product == null) {
            return wrapper;
        }
        wrapper.like(StringUtils.hasText(product.getProductName()), Product::getProductName, product.getProductName());
        Map<String, Object> params = product.getParams();
        String beginTime = MapUtils.getString(params, "beginTime");
        String endTime = MapUtils.getString(params, "endTime");
        if (StringUtils.hasText(beginTime)) {
            LocalDateTime start = LocalDate.parse(beginTime, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
            wrapper.ge(Product::getCreateTime, start);
        }
        if (StringUtils.hasText(endTime)) {
            LocalDateTime end = LocalDate.parse(endTime, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(LocalTime.MAX);
            wrapper.le(Product::getCreateTime, end);
        }
        return wrapper;
    }
}
