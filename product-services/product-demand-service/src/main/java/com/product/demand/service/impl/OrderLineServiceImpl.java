package com.product.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.demand.common.constant.StatusConstants;
import com.product.demand.common.exception.ServiceException;
import com.product.demand.common.utils.StringUtils;
import com.product.demand.domain.entity.CustomerOrder;
import com.product.demand.domain.entity.OrderLine;
import com.product.demand.domain.vo.ProductionBatchView;
import com.product.demand.mapper.OrderLineMapper;
import com.product.demand.service.IOrderLineService;
import com.product.demand.service.MasterDataReferenceValidator;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
/**
 * 订单明细Service业务层处理（MyBatis-Plus；单体 product-demand OrderLineServiceImpl 移植）。
 *
 * <p>与单体的差异（均为跨域数据所有权重排，ADR-0005）：</p>
 * <ul>
 *   <li><b>跨域引用校验（Phase 3 新增）</b>：{@code product_id} 引用经
 *       {@link MasterDataReferenceValidator}（product-master-data-api 批量契约）校验，
 *       引用不存在或 master-data 不可用时拒绝写入（fail-closed）。单体同库时代由外键
 *       隐式保证的语义，在 database-per-service 下改为应用层显式校验；</li>
 *   <li>{@code selectOrderLineByOrderLineId} 的拆批批次列表恒为空列表
 *       （production_batch 归 planning 所有，Phase 4 经契约填充）；</li>
 *   <li>删除订单行不再级联删除 production_batch（跨服务表不可写；Phase 3 中
 *       planning_db 为空，行为与单体等价，Phase 4 接线 planning 契约）。</li>
 * </ul>
 */
@Service
public class OrderLineServiceImpl extends ServiceImpl<OrderLineMapper, OrderLine> implements IOrderLineService {

    @Autowired
    private MasterDataReferenceValidator masterDataReferenceValidator;

    /**
     * 查询订单明细
     *
     * @param orderLineId 订单明细主键
     * @return 订单明细
     */
    @Override
    public OrderLine selectOrderLineByOrderLineId(Long orderLineId) {
        OrderLine orderLine = getById(orderLineId);
        if (orderLine == null) {
            return null;
        }
        // production_batch 归 planning 所有（ADR-0005）：Phase 3 响应形状保持（空列表，
        // 与单体"订单行未拆批"场景一致）；Phase 4 经 product-planning 契约填充。
        List<ProductionBatchView> productionBatchList = Collections.emptyList();
        orderLine.setProductionBatchList(productionBatchList);
        return orderLine;
    }

    /**
     * 查询订单明细列表
     *
     * @param orderLine 查询条件
     * @return 订单明细集合
     */
    @Override
    public List<OrderLine> selectOrderLineList(OrderLine orderLine) {
        return list(buildQueryWrapper(orderLine));
    }

    /**
     * 分页查询订单明细列表
     *
     * @param page      分页参数
     * @param orderLine 查询条件
     * @return 分页结果
     */
    @Override
    public Page<OrderLine> selectOrderLinePage(Page<OrderLine> page, OrderLine orderLine) {
        return this.page(page, buildQueryWrapper(orderLine));
    }

    /**
     * 新增订单明细
     *
     * @param orderLine 订单明细
     * @return 是否成功
     */
    @Override
    public boolean insertOrderLine(OrderLine orderLine) {
        validateOrderExists(orderLine == null ? null : orderLine.getOrderId());
        if (orderLine != null) {
            masterDataReferenceValidator.requireProductsExist(Collections.singletonList(orderLine.getProductId()));
        }
        if (StringUtils.isEmpty(orderLine.getStatus())) {
            orderLine.setStatus(StatusConstants.NEW_ORDER_LINE);
        }
        boolean saved = save(orderLine);
        return saved;
    }

    /**
     * 批量新增订单明细
     *
     * @param orderLines 订单明细列表
     * @return 成功条数
     */
    @Override
    public int batchInsertOrderLine(List<OrderLine> orderLines) {
        if (CollectionUtils.isEmpty(orderLines)) {
            return 0;
        }
        orderLines.forEach(orderLine -> validateOrderExists(orderLine.getOrderId()));
        masterDataReferenceValidator.requireProductsExist(
                orderLines.stream().map(OrderLine::getProductId).toList());
        boolean success = saveBatch(orderLines);
        return success ? orderLines.size() : 0;
    }

    /**
     * 修改订单明细
     *
     * @param orderLine 订单明细
     * @return 是否成功
     */
    @Override
    public boolean updateOrderLine(OrderLine orderLine) {
        if (orderLine != null) {
            masterDataReferenceValidator.requireProductsExist(Collections.singletonList(orderLine.getProductId()));
        }
        boolean updated = updateById(orderLine);
        return updated;
    }

    /**
     * 批量删除订单明细
     *
     * @param orderLineIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteOrderLineByOrderLineIds(String[] orderLineIds) {
        if (orderLineIds == null || orderLineIds.length == 0) {
            return false;
        }
        // 单体此处级联删除 production_batch；该表归 planning 所有（ADR-0005），
        // Phase 3 不做跨服务清理（planning_db 为空 ⇒ 行为等价），Phase 4 经契约接线。
        return removeByIds(Arrays.asList(orderLineIds));
    }

    /**
     * 删除订单明细信息
     *
     * @param orderLineId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteOrderLineByOrderLineId(Long orderLineId) {
        // 同 deleteOrderLineByOrderLineIds：批次级联清理由 Phase 4 planning 契约承接。
        return removeById(orderLineId);
    }

    @Override
    public boolean release(Long orderLineId) {
        OrderLine orderLine = lambdaQuery().select(OrderLine::getOrderId, OrderLine::getStatus)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .last("limit 1").one();
        String status = orderLine.getStatus();
        CustomerOrder customerOrder = Db.lambdaQuery(CustomerOrder.class)
                .select(CustomerOrder::getStatus)
                .eq(CustomerOrder::getOrderId, orderLine.getOrderId())
                .last("limit 1")
                .one();
        if (customerOrder.getStatus().equals(StatusConstants.NEW_CUSTOMER_ORDER)) {
            throw new ServiceException("订单未确认");
        }
        if (status.equals(StatusConstants.IN_PRODUCTION_ORDER_LINE)) {
            throw new ServiceException("该订单行已投入生产");
        }
        if (status.equals(StatusConstants.DONE_ORDER_LINE)) {
            throw new ServiceException(("该订单行已完成"));
        }
        return lambdaUpdate().set(OrderLine::getStatus, StatusConstants.RELEASED_ORDER_LINE)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
    }

    @Override
    public boolean cancelRelease(Long orderLineId) {
        OrderLine orderLine = lambdaQuery().select(OrderLine::getOrderId, OrderLine::getStatus)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .last("limit 1").one();
        String status = orderLine.getStatus();
        if (status.equals(StatusConstants.IN_PRODUCTION_ORDER_LINE)) {
            throw new ServiceException("该订单行已投入生产");
        }
        if (status.equals(StatusConstants.DONE_ORDER_LINE)) {
            throw new ServiceException(("该订单行已完成"));
        }
        return lambdaUpdate().set(OrderLine::getStatus, StatusConstants.NEW_ORDER_LINE)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<OrderLine> buildQueryWrapper(OrderLine orderLine) {
        LambdaQueryWrapper<OrderLine> wrapper = new LambdaQueryWrapper<>();
        if (orderLine == null) {
            return wrapper;
        }
        wrapper.eq(orderLine.getOrderId() != null, OrderLine::getOrderId, orderLine.getOrderId());
        wrapper.eq(orderLine.getProductId() != null, OrderLine::getProductId, orderLine.getProductId());
        wrapper.eq(orderLine.getQty() != null, OrderLine::getQty, orderLine.getQty());
        wrapper.eq(orderLine.getStatus() != null, OrderLine::getStatus, orderLine.getStatus());
        return wrapper;
    }

    /**
     * 校验订单行归属的订单必须存在，避免明细指向不存在的订单。
     */
    private void validateOrderExists(Long orderId) {
        if (orderId == null) {
            throw new ServiceException("订单行必须指定所属订单ID");
        }
        if (!orderExists(orderId)) {
            throw new ServiceException("所属订单不存在: " + orderId);
        }
    }

    /**
     * 订单存在性查询（Db 工具访问点，拆出为可覆写方法以便单测替换数据访问）。
     */
    protected boolean orderExists(Long orderId) {
        return Db.lambdaQuery(CustomerOrder.class)
                .eq(CustomerOrder::getOrderId, orderId)
                .exists();
    }
}
