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
import com.product.demand.service.DemandDataVersionService;
import com.product.demand.service.IOrderLineService;
import com.product.demand.service.MasterDataReferenceValidator;
import com.product.demand.service.PlanningBatchClient;
import com.product.planning.api.dto.PlanningContracts;
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
 *   <li>{@code selectOrderLineByOrderLineId} 的拆批批次列表（Phase 4 已接线）：经
 *       {@link PlanningBatchClient} 从 planning 契约填充（production_batch 归 planning
 *       所有）；planning 不可用时降级为空列表并告警（详情恒可读，显式差异）；</li>
 *   <li>删除订单行级联（Phase 4 已接线）：与单体"删批次再删行"次序一致——先经
 *       planning 契约删除该行批次（fail-closed：planning 不可用则拒绝删除本地行），
 *       再删除本地行。单体在无 @Transactional 下为两条独立语句，服务化后无法跨服务
 *       共享事务；失败窗口收敛为"批次已删、行仍在"（可重试），方向与单体一致；</li>
 *   <li>所有订单行写路径同事务 bump demand_data_version（Phase 4 排程输入漂移检测）。</li>
 * </ul>
 */
@Service
public class OrderLineServiceImpl extends ServiceImpl<OrderLineMapper, OrderLine> implements IOrderLineService {

    @Autowired
    private MasterDataReferenceValidator masterDataReferenceValidator;

    @Autowired
    private PlanningBatchClient planningBatchClient;

    @Autowired
    private DemandDataVersionService demandDataVersionService;

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
        // production_batch 归 planning 所有（ADR-0005）：经 product-planning 契约填充
        // （Phase 4 接线）；planning 不可用时降级为空列表（PlanningBatchClient，详情恒可读）。
        orderLine.setProductionBatchList(toBatchViews(
                planningBatchClient.listBatchesByOrderLines(orderLineId)));
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
        if (saved) {
            demandDataVersionService.bump();
        }
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
        if (success) {
            demandDataVersionService.bump();
        }
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
        if (updated) {
            demandDataVersionService.bump();
        }
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
        List<Long> ids = Arrays.stream(orderLineIds)
                .map(Long::valueOf)
                .toList();
        // Phase 4 接线：与单体"先删批次、后删行"次序一致；批次删除经 planning 契约
        // （fail-closed：planning 不可用则本地行不删，见 PlanningBatchClient javadoc）。
        planningBatchClient.deleteBatchesByOrderLines(ids);
        boolean removed = removeByIds(Arrays.asList(orderLineIds));
        if (removed) {
            demandDataVersionService.bump();
        }
        return removed;
    }

    /**
     * 删除订单明细信息
     *
     * @param orderLineId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteOrderLineByOrderLineId(Long orderLineId) {
        // 同 deleteOrderLineByOrderLineIds：先经 planning 契约删除该行批次（fail-closed）。
        planningBatchClient.deleteBatchesByOrderLines(List.of(orderLineId));
        boolean removed = removeById(orderLineId);
        if (removed) {
            demandDataVersionService.bump();
        }
        return removed;
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
        boolean released = lambdaUpdate().set(OrderLine::getStatus, StatusConstants.RELEASED_ORDER_LINE)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
        if (released) {
            demandDataVersionService.bump();
        }
        return released;
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
        boolean cancelled = lambdaUpdate().set(OrderLine::getStatus, StatusConstants.NEW_ORDER_LINE)
                .eq(OrderLine::getOrderLineId, orderLineId)
                .update();
        if (cancelled) {
            demandDataVersionService.bump();
        }
        return cancelled;
    }

    /**
     * planning 契约批次视图 → demand 侧 ProductionBatchView（字段一一对应）。
     */
    private List<ProductionBatchView> toBatchViews(List<PlanningContracts.ProductionBatchViewDTO> batches) {
        if (CollectionUtils.isEmpty(batches)) {
            return Collections.emptyList();
        }
        return batches.stream().map(batch -> {
            ProductionBatchView view = new ProductionBatchView();
            view.setBatchId(batch.getBatchId());
            view.setOrderLineId(batch.getOrderLineId());
            view.setBatchQty(batch.getBatchQty());
            view.setStatus(batch.getStatus());
            view.setPlannedStart(batch.getPlannedStart());
            view.setPlannedEnd(batch.getPlannedEnd());
            view.setCreateTime(batch.getCreateTime());
            view.setUpdateTime(batch.getUpdateTime());
            return view;
        }).toList();
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
