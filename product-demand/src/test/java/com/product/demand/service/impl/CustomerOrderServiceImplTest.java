package com.product.demand.service.impl;

import com.product.common.constant.StatusConstants;
import com.product.common.exception.ServiceException;
import com.product.domain.entity.CustomerOrder;
import junit.framework.TestCase;
import java.util.HashMap;
import java.util.Map;

public class CustomerOrderServiceImplTest extends TestCase {

    public void testCheckShouldRejectInProductionOrder() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(901L,
                StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER));

        ServiceException exception = expectServiceException(() -> service.check(901L));

        assertEquals("该订单已投入生产，禁止修改", exception.getMessage());
    }

    public void testCheckShouldRejectDoneOrder() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(901L,
                StatusConstants.DONE_CUSTOMER_ORDER));

        ServiceException exception = expectServiceException(() -> service.check(901L));

        assertEquals("该订单已经完成，禁止修改", exception.getMessage());
    }

    public void testCancelCheckShouldRejectInProductionOrder() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(902L,
                StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER));

        ServiceException exception = expectServiceException(() -> service.cancelCheck(902L));

        assertEquals("该订单已投入生产，禁止修改", exception.getMessage());
    }

    public void testCancelCheckShouldRejectDoneOrder() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(902L,
                StatusConstants.DONE_CUSTOMER_ORDER));

        ServiceException exception = expectServiceException(() -> service.cancelCheck(902L));

        assertEquals("该订单已经完成，禁止修改", exception.getMessage());
    }

    public void testUpdateShouldRejectInProductionOrder() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(903L,
                StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER));

        CustomerOrder customerOrder = order(903L, StatusConstants.CONFIRMED_CUSTOMER_ORDER);
        ServiceException exception = expectServiceException(() -> service.updateCustomerOrder(customerOrder));

        assertEquals("该订单已投入生产，禁止修改", exception.getMessage());
    }

    public void testUpdateShouldRejectDoneOrder() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(904L,
                StatusConstants.DONE_CUSTOMER_ORDER));

        CustomerOrder customerOrder = order(904L, StatusConstants.NEW_CUSTOMER_ORDER);
        ServiceException exception = expectServiceException(() -> service.updateCustomerOrder(customerOrder));

        assertEquals("该订单已经完成，禁止修改", exception.getMessage());
    }

    public void testUpdateShouldRejectIllegalStatusTransition() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(905L,
                StatusConstants.NEW_CUSTOMER_ORDER));

        CustomerOrder customerOrder = order(905L, StatusConstants.DONE_CUSTOMER_ORDER);
        ServiceException exception = expectServiceException(() -> service.updateCustomerOrder(customerOrder));

        assertEquals("订单状态只能在 NEW 和 CONFIRMED 之间流转", exception.getMessage());
    }

    public void testUpdateShouldKeepCurrentStatusWhenPayloadOmitsStatus() {
        RecordingCustomerOrderService service = new RecordingCustomerOrderService(order(906L,
                StatusConstants.CONFIRMED_CUSTOMER_ORDER));

        CustomerOrder customerOrder = new CustomerOrder();
        customerOrder.setOrderId(906L);
        customerOrder.setPriority(2L);

        boolean updated = service.updateCustomerOrder(customerOrder);

        assertTrue(updated);
        assertNotNull(service.updatedOrder);
        assertSame(customerOrder, service.updatedOrder);
        assertEquals(StatusConstants.CONFIRMED_CUSTOMER_ORDER, service.updatedOrder.getStatus());
    }

    private ServiceException expectServiceException(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (ServiceException exception) {
            return exception;
        }
        fail("expected ServiceException");
        return null;
    }

    private static CustomerOrder order(Long orderId, String status) {
        CustomerOrder customerOrder = new CustomerOrder();
        customerOrder.setOrderId(orderId);
        customerOrder.setStatus(status);
        return customerOrder;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static final class RecordingCustomerOrderService extends CustomerOrderServiceImpl {
        private final Map<Long, CustomerOrder> orders = new HashMap<>();
        private CustomerOrder updatedOrder;

        private RecordingCustomerOrderService(CustomerOrder... customerOrders) {
            for (CustomerOrder customerOrder : customerOrders) {
                orders.put(customerOrder.getOrderId(), customerOrder);
            }
        }

        @Override
        protected CustomerOrder loadCustomerOrderForGuard(Long orderId) {
            return orders.get(orderId);
        }

        @Override
        protected boolean persistCustomerOrder(CustomerOrder customerOrder) {
            this.updatedOrder = customerOrder;
            orders.put(customerOrder.getOrderId(), customerOrder);
            return true;
        }

        @Override
        protected boolean persistCustomerOrderStatus(Long orderId, String targetStatus) {
            CustomerOrder customerOrder = orders.get(orderId);
            customerOrder.setStatus(targetStatus);
            return true;
        }
    }
}
