package com.product.planning.service.impl;

import com.product.demand.api.DemandBatchQueryApi;
import com.product.demand.api.dto.AllocationCommand;
import com.product.demand.api.dto.DemandQueryRequests;
import com.product.demand.api.dto.OrderDTO;
import com.product.demand.api.dto.OrderLineDTO;
import com.product.masterdata.api.MasterDataBatchQueryApi;
import com.product.masterdata.api.dto.DataVersionResponse;
import com.product.masterdata.api.dto.ProductBatchQueryRequest;
import com.product.masterdata.api.dto.ProductBatchResponse;
import com.product.masterdata.api.dto.ProductDTO;
import com.product.masterdata.api.dto.ProductRouteDTO;
import com.product.masterdata.api.dto.ResourceBatchQueryRequest;
import com.product.masterdata.api.dto.ResourceBatchResponse;
import com.product.masterdata.api.dto.ResourceDTO;
import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.StatusConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.model.Product;
import com.product.planning.domain.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排程输入快照加载器单测（Phase 4）：
 * 1) AVAILABLE 状态过滤与资源聚合映射（与单体 loadAvailableResources/attachMachineDetails 语义一致）；
 * 2) 产品映射（模具参数 + 启用路线工序 sequence 升序）；
 * 3) 版本漂移规则（落库前复核失败 → 显式 ServiceException）。
 */
@ExtendWith(MockitoExtension.class)
class SchedulingSnapshotLoaderTest {

    @Mock
    private DemandBatchQueryApi demandBatchQueryApi;

    @Mock
    private MasterDataBatchQueryApi masterDataBatchQueryApi;

    @Mock
    private com.product.planning.config.ServiceIdentityTokenProvider serviceIdentityTokenProvider;

    private SchedulingSnapshotLoader newLoader() {
        return new SchedulingSnapshotLoader(demandBatchQueryApi, masterDataBatchQueryApi,
                serviceIdentityTokenProvider);
    }

    @Test
    void loadAvailableResourcesShouldFilterNonAvailableAndAttachMachineDetails() {
        ResourceDTO available = resource(101L, StatusConstants.AVAILABLE_RESOURCE_STATUS,
                ResourceConstants.RESOURCE_TYPE_MACHINE, 30);
        ResourceDTO down = resource(102L, StatusConstants.DOWN_RESOURCE_STATUS,
                ResourceConstants.RESOURCE_TYPE_MACHINE, 10);

        ResourceBatchResponse response = new ResourceBatchResponse();
        response.setResources(List.of(available, down));
        when(masterDataBatchQueryApi.getResources(any(ResourceBatchQueryRequest.class))).thenReturn(response);

        List<Resource> resources = newLoader().loadAvailableResources(
                List.of(ResourceConstants.RESOURCE_TYPE_MACHINE));

        assertEquals(1, resources.size());
        assertEquals(101L, resources.get(0).getResourceId());
        assertEquals(30, resources.get(0).getMachine().getDefaultSetupTimeMin());
    }

    @Test
    void loadProductsShouldMapMoldParamsAndSortedActiveOperations() {
        ProductRouteDTO.RouteOperationDTO op2 = new ProductRouteDTO.RouteOperationDTO();
        op2.setOpCode("INJECT");
        op2.setSequence(2);
        op2.setEligibleResourceRule("RULE_INJECT_MACHINE");
        op2.setStdTimeModel("TM_INJECT_A2");
        op2.setQueuePolicy("FIFO");
        ProductRouteDTO.RouteOperationDTO op1 = new ProductRouteDTO.RouteOperationDTO();
        op1.setOpCode("SETUP");
        op1.setSequence(1);
        op1.setEligibleResourceRule("RULE_SETUP_MACHINE");
        op1.setStdTimeModel("TM_SETUP_BASE");
        op1.setQueuePolicy("FIFO");
        // 故意乱序返回，验证 loader 排序（sequence 升序）
        ProductRouteDTO route = new ProductRouteDTO();
        route.setRouteId(11L);
        route.setProductId(100L);
        route.setIsActive(1);
        route.setOperations(List.of(op2, op1));

        ProductDTO dto = new ProductDTO();
        dto.setProductId(100L);
        dto.setMaterialCode("PP");
        dto.setColorCode("RED");
        dto.setActiveRoute(route);
        ProductDTO.ProductMoldParamDTO param = new ProductDTO.ProductMoldParamDTO();
        param.setMoldId(201L);
        param.setCycleTimeSec(new java.math.BigDecimal("30"));
        param.setCavity(2);
        param.setYieldRate(java.math.BigDecimal.ONE);
        param.setUtilization(java.math.BigDecimal.ONE);
        dto.setMoldParams(List.of(param));

        ProductBatchResponse response = new ProductBatchResponse();
        response.setProducts(List.of(dto));
        when(masterDataBatchQueryApi.getProducts(any(ProductBatchQueryRequest.class))).thenReturn(response);

        Map<Long, Product> products = newLoader().loadProducts(List.of(100L));

        assertEquals(1, products.size());
        Product product = products.get(100L);
        assertEquals("PP", product.getMaterialCode());
        assertEquals(1, product.getMoldParams().size());
        assertEquals(2, product.getActiveOperations().size());
        assertEquals("SETUP", product.getActiveOperations().get(0).getOpCode());
        assertEquals("INJECT", product.getActiveOperations().get(1).getOpCode());
    }

    @Test
    void verifyUnchangedShouldRejectDemandVersionDrift() {
        AllocationCommand.DataVersionResponse demandV1 =
                new AllocationCommand.DataVersionResponse(3L);
        DataVersionResponse masterDataV1 = new DataVersionResponse(7L);
        AllocationCommand.DataVersionResponse demandV2 =
                new AllocationCommand.DataVersionResponse(4L);
        when(demandBatchQueryApi.getDataVersion()).thenReturn(demandV1, demandV2);
        when(masterDataBatchQueryApi.getDataVersion()).thenReturn(masterDataV1);

        SchedulingSnapshotLoader loader = newLoader();
        SchedulingSnapshotLoader.Versions baseline = loader.captureVersions();

        ServiceException exception = assertThrows(ServiceException.class,
                () -> loader.verifyUnchanged(baseline));
        assertTrue(exception.getMessage().contains("需求域版本漂移"));
    }

    @Test
    void loadOrderLinesShouldEvictTokenAndRetryOnceOnAuthShapedResponse() {
        // 提供方对认证失败返回单体契约（HTTP 200 + code 401 信封）——反序列化后表现为
        // orderLines 缺失。首次调用命中该形态 → evict 服务身份令牌 → 重试一次成功。
        OrderLineDTO good = new OrderLineDTO();
        good.setOrderLineId(1001L);
        good.setOrderId(9001L);
        good.setProductId(100L);
        good.setStatus("RELEASED");
        OrderDTO.OrderLineBatchResponse bad = new OrderDTO.OrderLineBatchResponse();
        OrderDTO.OrderLineBatchResponse ok = new OrderDTO.OrderLineBatchResponse();
        ok.setOrderLines(List.of(good));
        when(demandBatchQueryApi.getOrderLines(any(DemandQueryRequests.OrderLineBatchQueryRequest.class)))
                .thenReturn(bad, ok);

        Map<Long, com.product.planning.domain.model.OrderLineSnapshot> lines =
                newLoader().loadOrderLines(List.of(1001L));

        assertEquals(1, lines.size());
        assertEquals(100L, lines.get(1001L).getProductId());
        verify(demandBatchQueryApi, org.mockito.Mockito.times(2))
                .getOrderLines(any(DemandQueryRequests.OrderLineBatchQueryRequest.class));
        verify(serviceIdentityTokenProvider).evict();
    }

    @Test
    void loadOrderLinesShouldGiveUpAfterSingleRetry() {
        // 重试一次仍为认证形态 → 按响应异常处理（不再循环重试）
        OrderDTO.OrderLineBatchResponse bad = new OrderDTO.OrderLineBatchResponse();
        when(demandBatchQueryApi.getOrderLines(any(DemandQueryRequests.OrderLineBatchQueryRequest.class)))
                .thenReturn(bad, bad);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> newLoader().loadOrderLines(List.of(1001L)));
        assertTrue(exception.getMessage().contains("需求服务响应异常"));
        verify(demandBatchQueryApi, org.mockito.Mockito.times(2))
                .getOrderLines(any(DemandQueryRequests.OrderLineBatchQueryRequest.class));
        verify(serviceIdentityTokenProvider).evict();
    }

    @Test
    void verifyUnchangedShouldAcceptStableVersions() {
        when(demandBatchQueryApi.getDataVersion()).thenReturn(new AllocationCommand.DataVersionResponse(3L));
        when(masterDataBatchQueryApi.getDataVersion()).thenReturn(new DataVersionResponse(7L));

        SchedulingSnapshotLoader loader = newLoader();
        SchedulingSnapshotLoader.Versions baseline = loader.captureVersions();

        loader.verifyUnchanged(baseline);
    }

    private ResourceDTO resource(Long id, String status, String type, Integer defaultSetupTimeMin) {
        ResourceDTO dto = new ResourceDTO();
        dto.setResourceId(id);
        dto.setStatus(status);
        dto.setResourceType(type);
        dto.setCalendarId(1L);
        ResourceDTO.MachineDTO machine = new ResourceDTO.MachineDTO();
        machine.setMachineId(id);
        machine.setDefaultSetupTimeMin(defaultSetupTimeMin);
        dto.setMachine(machine);
        return dto;
    }
}
