package com.product.demand.service;

import com.product.demand.common.exception.ServiceException;
import com.product.masterdata.api.MasterDataBatchQueryApi;
import com.product.masterdata.api.dto.ProductBatchQueryRequest;
import com.product.masterdata.api.dto.ProductExistenceResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 跨域产品引用校验（Phase 3；ADR-0002 决策 5、ADR-0005 §2）。
 *
 * <p>order_line.product_id 在目标态是跨服务业务引用（product 归 master_data_db 所有，
 * demand_db 无 product 表）。本组件经 product-master-data-api 批量契约校验引用存在性，
 * 替代单体的同库 join/外键语义。</p>
 *
 * <p><b>fail-closed 语义（契约明确，无静默默认）</b>：</p>
 * <ul>
 *   <li>master-data 不可达/超时/5xx/限流（FeignException）：拒绝本次写入并抛出
 *       {@code ServiceException("主数据服务不可用...")}，绝不降级为"跳过校验放行"；</li>
 *   <li>响应形状非法（existingIds 为 null —— 含 master-data 返回 401 错误体的场景，
 *       其错误信封为 HTTP 200 + code 401，反序列化后字段为 null）：同样拒绝写入；</li>
 *   <li>校验通过但引用不存在：{@code ServiceException("产品不存在: productId=...")}，
 *       文案与单体"引用实体不存在"的消息风格一致。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterDataReferenceValidator {

    private final MasterDataBatchQueryApi masterDataBatchQueryApi;

    /**
     * 校验给定产品 ID 全部存在；{@code productIds} 为空或全为 null 时跳过
     * （order_line.product_id 可空，与单体 DDL/行为一致）。
     */
    public void requireProductsExist(Collection<Long> productIds) {
        if (CollectionUtils.isEmpty(productIds)) {
            return;
        }
        List<Long> distinctIds = productIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return;
        }

        ProductExistenceResponse response;
        try {
            response = masterDataBatchQueryApi.existsProducts(new ProductBatchQueryRequest(distinctIds));
        } catch (FeignException e) {
            log.error("主数据服务调用失败，产品引用校验拒绝写入: status={} requestIds={}", e.status(), distinctIds, e);
            throw new ServiceException("主数据服务不可用，无法校验产品引用，请稍后重试");
        }

        if (response == null || response.getExistingIds() == null) {
            log.error("主数据服务响应非法（可能未携带有效凭证或契约不匹配），产品引用校验拒绝写入: requestIds={}", distinctIds);
            throw new ServiceException("主数据服务响应异常，无法校验产品引用，请稍后重试");
        }

        Set<Long> existing = new HashSet<>(response.getExistingIds());
        List<Long> missing = distinctIds.stream()
                .filter(id -> !existing.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ServiceException("产品不存在: productId=" +
                    missing.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        }
    }
}
