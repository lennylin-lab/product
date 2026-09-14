package com.product.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.demand.common.constant.StatusConstants;
import com.product.demand.common.exception.ServiceException;
import com.product.demand.common.utils.StringUtils;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.domain.vo.CustomerOrderVO;
import com.product.demand.mapper.CustomerOrderMapper;
import com.product.demand.service.ICustomerOrderService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 订单Service业务层处理（MyBatis-Plus；单体 product-demand CustomerOrderServiceImpl 移植）。
 *
 * <p>状态机（baselines.md §2.2 冻结）：NEW ↔ CONFIRMED 人工流转；
 * IN_PRODUCTION/DONE 禁止修改；IN_PRODUCTION → DONE 由执行事件链路推进（Phase 5）。</p>
 *
 * @author product
 * @date 2025-12-26
 */
@Service
public class CustomerOrderServiceImpl extends ServiceImpl<CustomerOrderMapper, CustomerOrder> implements ICustomerOrderService {

    /** Phase 4：需求域数据版本计数（排程输入快照漂移检测；订单为排程输入）。 */
    @Autowired
    private com.product.demand.service.DemandDataVersionService demandDataVersionService;

    /** Phase 5：批次状态投影清理（订单删除时随行清理事件链聚合输入）。 */
    @Autowired
    private com.product.demand.service.PlanningBatchStateService planningBatchStateService;

    /** 允许人工流转的订单状态集合：新建和已确认之间可以互相切换 */
    private static final Set<String> ALLOWED_MANUAL_ORDER_STATUSES = Set.of(
            StatusConstants.NEW_CUSTOMER_ORDER,
            StatusConstants.CONFIRMED_CUSTOMER_ORDER
    );

    @Autowired
    CustomerOrderMapper customerOrderMapper;


    /**
     * 查询订单
     *
     * @param orderId 订单主键
     * @return 订单
     */
    @Override
    public CustomerOrder selectCustomerOrderByOrderId(Long orderId) {
        CustomerOrder customerOrder = getById(orderId);
        if (customerOrder == null) {
            return null;
        }
        List<OrderLine> orderLineList = baseMapper.selectOrderLineList(orderId);
        customerOrder.setOrderLineList(orderLineList);
        return customerOrder;
    }

    /**
     * 查询订单列表
     *
     * @param customerOrder 查询条件
     * @return 订单集合
     */
    @Override
    public List<CustomerOrder> selectCustomerOrderList(CustomerOrder customerOrder) {
        return list(buildQueryWrapper(customerOrder));
    }

    /**
     * 分页查询订单列表
     *
     * @param page      分页参数
     * @param customerOrder 查询条件
     * @return 分页结果
     */
    @Override
    public Page<CustomerOrderVO> selectCustomerOrderPage(Page<CustomerOrderVO> page, CustomerOrder customerOrder) {
        return customerOrderMapper.selectCustomerOrderPage(page, customerOrder);
    }

    /**
     * 新增订单
     *
     * @param customerOrder 订单
     * @return 是否成功
     */
    @Override
    public boolean insertCustomerOrder(CustomerOrder customerOrder) {
        if (customerOrder.getOrderId() == null) {
            customerOrder.setOrderId(IdWorker.getId());
        }
        if (StringUtils.isEmpty(customerOrder.getStatus())) {
            customerOrder.setStatus(StatusConstants.NEW_CUSTOMER_ORDER);
        }
        boolean saved = save(customerOrder);
        if (saved) {
            demandDataVersionService.bump();
        }
        return saved;
    }

    /**
     * 批量新增订单
     *
     * @param customerOrders 订单列表
     * @return 成功条数
     */
    @Override
    public int batchInsertCustomerOrder(List<CustomerOrder> customerOrders) {
        if (CollectionUtils.isEmpty(customerOrders)) {
            return 0;
        }
        customerOrders.forEach(order -> {
            if (order.getOrderId() == null) {
                order.setOrderId(IdWorker.getId());
            }
        });
        boolean success = saveBatch(customerOrders);
        if (success) {
            demandDataVersionService.bump();
        }
        return success ? customerOrders.size() : 0;
    }

    /**
     * 修改订单
     *
     * @param customerOrder 订单
     * @return 是否成功
     */
    @Override
    public boolean updateCustomerOrder(CustomerOrder customerOrder) {
        CustomerOrder currentOrder = requireCustomerOrder(customerOrder.getOrderId());
        String targetStatus = StringUtils.isEmpty(customerOrder.getStatus())
                ? currentOrder.getStatus()
                : customerOrder.getStatus();
        validateOrderStatusTransition(currentOrder.getStatus(), targetStatus);
        customerOrder.setStatus(targetStatus);
        boolean updated = persistCustomerOrder(customerOrder);
        if (updated) {
            demandDataVersionService.bump();
        }
        return updated;
    }

    /**
     * 批量删除订单
     *
     * @param orderIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteCustomerOrderByOrderIds(String[] orderIds) {
        if (orderIds == null || orderIds.length == 0) {
            return false;
        }
        // 与单体一致：删除订单行（不触碰 planning 批次——单体删单也不清批次，冻结行为）。
        // 订单行/订单均为排程输入，删除后 bump 版本计数。
        List<Long> orderIdList = Arrays.stream(orderIds).map(Long::valueOf).toList();
        // Phase 5：删除前收集订单行ID，随行清理批次状态投影（事件链聚合输入）
        List<Long> lineIds = com.baomidou.mybatisplus.extension.toolkit.Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId)
                .in(OrderLine::getOrderId, orderIdList)
                .list()
                .stream()
                .map(OrderLine::getOrderLineId)
                .filter(java.util.Objects::nonNull)
                .toList();
        planningBatchStateService.deleteByOrderLineIds(lineIds);
        baseMapper.deleteOrderLineByOrderIds(orderIds);
        boolean removed = removeByIds(Arrays.asList(orderIds));
        if (removed) {
            demandDataVersionService.bump();
        }
        return removed;
    }

    /**
     * 删除订单信息
     *
     * @param orderId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteCustomerOrderByOrderId(Long orderId) {
        // Phase 5：删除前收集订单行ID，随行清理批次状态投影
        List<Long> lineIds = com.baomidou.mybatisplus.extension.toolkit.Db.lambdaQuery(OrderLine.class)
                .select(OrderLine::getOrderLineId)
                .eq(OrderLine::getOrderId, orderId)
                .list()
                .stream()
                .map(OrderLine::getOrderLineId)
                .filter(java.util.Objects::nonNull)
                .toList();
        planningBatchStateService.deleteByOrderLineIds(lineIds);
        baseMapper.deleteOrderLineByOrderId(orderId);
        boolean removed = removeById(orderId);
        if (removed) {
            demandDataVersionService.bump();
        }
        return removed;
    }

    /** 确认订单：将订单状态从 NEW 变更为 CONFIRMED，确认后可进入排程流程 */
    @Override
    public boolean check(Long orderId) {
        CustomerOrder customerOrder = requireCustomerOrder(orderId);
        validateOrderStatusTransition(customerOrder.getStatus(), StatusConstants.CONFIRMED_CUSTOMER_ORDER);
        boolean checked = persistCustomerOrderStatus(orderId, StatusConstants.CONFIRMED_CUSTOMER_ORDER);
        if (checked) {
            demandDataVersionService.bump();
        }
        return checked;
    }

    /** 反确认订单：将订单状态从 CONFIRMED 回退为 NEW */
    @Override
    public boolean cancelCheck(Long orderId) {
        CustomerOrder customerOrder = requireCustomerOrder(orderId);
        validateOrderStatusTransition(customerOrder.getStatus(), StatusConstants.NEW_CUSTOMER_ORDER);
        boolean cancelled = persistCustomerOrderStatus(orderId, StatusConstants.NEW_CUSTOMER_ORDER);
        if (cancelled) {
            demandDataVersionService.bump();
        }
        return cancelled;
    }

    /** 获取订单实体，不存在时抛出异常 */
    protected CustomerOrder requireCustomerOrder(Long orderId) {
        if (orderId == null) {
            throw new ServiceException("订单不存在");
        }
        CustomerOrder customerOrder = loadCustomerOrderForGuard(orderId);
        if (customerOrder == null) {
            throw new ServiceException("订单不存在");
        }
        return customerOrder;
    }

    /** 轻量加载订单状态，用于状态校验（只查 orderId 和 status，避免加载完整实体） */
    protected CustomerOrder loadCustomerOrderForGuard(Long orderId) {
        return lambdaQuery()
                .select(CustomerOrder::getOrderId, CustomerOrder::getStatus)
                .eq(CustomerOrder::getOrderId, orderId)
                .last("limit 1")
                .one();
    }

    /** 持久化订单更新 */
    protected boolean persistCustomerOrder(CustomerOrder customerOrder) {
        return updateById(customerOrder);
    }

    /** 单独持久化订单状态变更（只更新 status 字段，避免整行更新） */
    protected boolean persistCustomerOrderStatus(Long orderId, String targetStatus) {
        return lambdaUpdate()
                .set(CustomerOrder::getStatus, targetStatus)
                .eq(CustomerOrder::getOrderId, orderId)
                .update();
    }

    /**
     * 校验订单状态流转是否合法。
     *
     * <p>规则：
     * <ul>
     *   <li>已投入生产（IN_PRODUCTION）或已完成（DONE）的订单禁止修改</li>
     *   <li>目标状态不能为空</li>
     *   <li>目标状态只能在 NEW 和 CONFIRMED 之间</li>
     *   <li>当前状态也必须是 NEW 或 CONFIRMED 才允许人工流转</li>
     * </ul>
     */
    protected void validateOrderStatusTransition(String currentStatus, String targetStatus) {
        if (StatusConstants.IN_PRODUCTION_CUSTOMER_ORDER.equals(currentStatus)) {
            throw new ServiceException("该订单已投入生产，禁止修改");
        }
        if (StatusConstants.DONE_CUSTOMER_ORDER.equals(currentStatus)) {
            throw new ServiceException("该订单已经完成，禁止修改");
        }
        if (StringUtils.isEmpty(targetStatus)) {
            throw new ServiceException("订单状态不能为空");
        }
        if (!ALLOWED_MANUAL_ORDER_STATUSES.contains(targetStatus)) {
            throw new ServiceException("订单状态只能在 NEW 和 CONFIRMED 之间流转");
        }
        if (!ALLOWED_MANUAL_ORDER_STATUSES.contains(currentStatus)) {
            throw new ServiceException("当前订单状态不允许人工修改");
        }
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<CustomerOrder> buildQueryWrapper(CustomerOrder customerOrder) {
        LambdaQueryWrapper<CustomerOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(customerOrder.getCustomerId() != null, CustomerOrder::getCustomerId, customerOrder.getCustomerId());
        wrapper.eq(customerOrder.getDueDate() != null, CustomerOrder::getDueDate, customerOrder.getDueDate());
        wrapper.eq(customerOrder.getPriority() != null, CustomerOrder::getPriority, customerOrder.getPriority());
        wrapper.eq(customerOrder.getStatus() != null, CustomerOrder::getStatus, customerOrder.getStatus());
        return wrapper;
    }
}
