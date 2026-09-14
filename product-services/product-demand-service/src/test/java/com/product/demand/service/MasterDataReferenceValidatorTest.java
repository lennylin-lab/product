package com.product.demand.service;

import com.product.demand.common.exception.ServiceException;
import com.product.masterdata.api.MasterDataBatchQueryApi;
import com.product.masterdata.api.dto.ProductBatchQueryRequest;
import com.product.masterdata.api.dto.ProductExistenceResponse;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨域产品引用校验单测（Phase 3：ADR-0002 决策 5 的 fail-closed 契约）。
 */
@ExtendWith(MockitoExtension.class)
class MasterDataReferenceValidatorTest {

    @Mock
    private MasterDataBatchQueryApi masterDataBatchQueryApi;

    @InjectMocks
    private MasterDataReferenceValidator validator;

    @Test
    void emptyProductIdsShouldSkipRemoteValidation() {
        validator.requireProductsExist(null);
        validator.requireProductsExist(Collections.emptyList());
        validator.requireProductsExist(Collections.singletonList(null));

        verify(masterDataBatchQueryApi, never()).existsProducts(any());
    }

    @Test
    void shouldDeduplicateIdsBeforeRemoteCall() {
        ProductExistenceResponse response = new ProductExistenceResponse();
        response.setSnapshotVersion(1);
        response.setExistingIds(List.of(5L, 6L));
        when(masterDataBatchQueryApi.existsProducts(any())).thenReturn(response);

        validator.requireProductsExist(java.util.Arrays.asList(5L, 6L, 5L, null));

        ArgumentCaptor<ProductBatchQueryRequest> captor = ArgumentCaptor.forClass(ProductBatchQueryRequest.class);
        verify(masterDataBatchQueryApi).existsProducts(captor.capture());
        assertEquals(List.of(5L, 6L), captor.getValue().getProductIds());
    }

    @Test
    void allProductsExistingShouldPass() {
        ProductExistenceResponse response = new ProductExistenceResponse();
        response.setSnapshotVersion(7);
        response.setExistingIds(List.of(5L));
        when(masterDataBatchQueryApi.existsProducts(any())).thenReturn(response);

        validator.requireProductsExist(List.of(5L));
    }

    @Test
    void missingProductShouldBeRejectedWithProductId() {
        ProductExistenceResponse response = new ProductExistenceResponse();
        response.setSnapshotVersion(7);
        response.setExistingIds(List.of(5L));
        when(masterDataBatchQueryApi.existsProducts(any())).thenReturn(response);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> validator.requireProductsExist(List.of(5L, 8L)));
        assertEquals("产品不存在: productId=8", exception.getMessage());
    }

    @Test
    void masterDataUnavailableShouldRejectWrite() {
        Request request = Request.create(Request.HttpMethod.POST, "/internal/master-data/products/exists",
                java.util.Map.of(), null, StandardCharsets.UTF_8, null);
        feign.Response response = feign.Response.builder()
                .status(503)
                .reason("Service Unavailable")
                .request(request)
                .headers(java.util.Map.of())
                .build();
        when(masterDataBatchQueryApi.existsProducts(any()))
                .thenThrow(FeignException.errorStatus("masterDataBatchQueryClient", response));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> validator.requireProductsExist(List.of(5L)));
        assertEquals("主数据服务不可用，无法校验产品引用，请稍后重试", exception.getMessage());
    }

    @Test
    void malformedResponseShouldRejectWrite() {
        // master-data 鉴权失败的错误信封为 HTTP 200 + code 401，反序列化后 existingIds 为 null
        ProductExistenceResponse response = new ProductExistenceResponse();
        when(masterDataBatchQueryApi.existsProducts(any())).thenReturn(response);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> validator.requireProductsExist(List.of(5L)));
        assertEquals("主数据服务响应异常，无法校验产品引用，请稍后重试", exception.getMessage());
    }
}
