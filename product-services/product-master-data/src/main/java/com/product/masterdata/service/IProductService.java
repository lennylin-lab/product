package com.product.masterdata.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.masterdata.domain.entity.Product;

import java.util.List;

/**
 * 产品Service接口（单体 product-demand IProductService 移植；产品归 master_data_db，
 * 对外路由保持 /demand/product，baselines.md §1.2）。
 */
public interface IProductService extends IService<Product> {
    Product selectProductByProductId(Long productId);
    List<Product> selectProductList(Product product);
    Page<Product> selectProductPage(Page<Product> page, Product product);
    boolean insertProduct(Product product);
    int batchInsertProduct(List<Product> products);
    boolean updateProduct(Product product);
    boolean deleteProductByProductIds(Long[] productIds);
    boolean deleteProductByProductId(Long productId);
}
