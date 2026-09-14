package com.product.demand.service;

import com.product.demand.common.exception.ServiceException;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.mapper.OrderLineMapper;
import com.product.demand.service.impl.OrderLineServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单行服务单测（Phase 3）：
 * 1) 订单行必须指定所属订单ID（单体行为冻结）；
 * 2) product_id 跨域引用校验在写入前执行，校验失败时拒绝写入（fail-closed）。
 */
@ExtendWith(MockitoExtension.class)
class OrderLineServiceImplTest {

    @Mock
    private OrderLineMapper orderLineMapper;

    @Mock
    private MasterDataReferenceValidator masterDataReferenceValidator;

    @Mock
    private com.product.demand.service.PlanningBatchClient planningBatchClient;

    @Mock
    private com.product.demand.service.DemandDataVersionService demandDataVersionService;

    @Test
    void insertOrderLineShouldRequireOrderId() {
        OrderLineServiceImpl service = newService();

        OrderLine orderLine = new OrderLine();
        orderLine.setProductId(5L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.insertOrderLine(orderLine));
        assertEquals("订单行必须指定所属订单ID", exception.getMessage());
        verify(masterDataReferenceValidator, never()).requireProductsExist(anyCollection());
    }

    @Test
    void insertOrderLineShouldValidateProductReferenceBeforeSave() {
        OrderLineServiceImpl service = newService(true);

        OrderLine orderLine = new OrderLine();
        orderLine.setOrderId(1L);
        orderLine.setProductId(5L);
        when(orderLineMapper.insert(any(OrderLine.class))).thenReturn(1);

        assertTrue(service.insertOrderLine(orderLine));
        verify(masterDataReferenceValidator).requireProductsExist(Collections.singletonList(5L));
        verify(orderLineMapper).insert(orderLine);
    }

    @Test
    void insertOrderLineShouldRejectWriteWhenReferenceValidationFails() {
        OrderLineServiceImpl service = newService(true);
        doThrow(new ServiceException("产品不存在: productId=5"))
                .when(masterDataReferenceValidator).requireProductsExist(anyCollection());

        OrderLine orderLine = new OrderLine();
        orderLine.setOrderId(1L);
        orderLine.setProductId(5L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.insertOrderLine(orderLine));
        assertEquals("产品不存在: productId=5", exception.getMessage());
        verify(orderLineMapper, never()).insert(any(OrderLine.class));
    }

    @Test
    void updateOrderLineShouldValidateProductReferenceBeforeUpdate() {
        OrderLineServiceImpl service = newService();

        OrderLine orderLine = new OrderLine();
        orderLine.setOrderLineId(1L);
        orderLine.setProductId(5L);
        when(orderLineMapper.updateById(any(OrderLine.class))).thenReturn(1);

        assertTrue(service.updateOrderLine(orderLine));
        verify(masterDataReferenceValidator).requireProductsExist(Collections.singletonList(5L));
        verify(orderLineMapper).updateById(orderLine);
    }

    @Test
    void updateOrderLineShouldRejectWriteWhenReferenceValidationFails() {
        OrderLineServiceImpl service = newService();
        doThrow(new ServiceException("产品不存在: productId=5"))
                .when(masterDataReferenceValidator).requireProductsExist(anyCollection());

        OrderLine orderLine = new OrderLine();
        orderLine.setOrderLineId(1L);
        orderLine.setProductId(5L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.updateOrderLine(orderLine));
        assertEquals("产品不存在: productId=5", exception.getMessage());
        verify(orderLineMapper, never()).updateById(any(OrderLine.class));
    }

    /**
     * 构造被测服务：注入 mock 依赖；orderExists 查询（Db 工具）用可覆写桩替换。
     */
    private OrderLineServiceImpl newService() {
        return newService(false);
    }

    private OrderLineServiceImpl newService(boolean orderExists) {
        OrderLineServiceImpl service = new OrderLineServiceImpl() {
            @Override
            protected boolean orderExists(Long orderId) {
                return orderExists;
            }
        };
        ReflectionTestUtils.setField(service, "baseMapper", orderLineMapper);
        ReflectionTestUtils.setField(service, "masterDataReferenceValidator", masterDataReferenceValidator);
        // Phase 4 新增依赖：删行级联批次契约与需求域版本计数（写路径成功后 bump）
        ReflectionTestUtils.setField(service, "planningBatchClient", planningBatchClient);
        ReflectionTestUtils.setField(service, "demandDataVersionService", demandDataVersionService);
        return service;
    }
}
