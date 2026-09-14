package com.product.planning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.planning.common.constant.StatusConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.dto.BatchSearchDTO;
import com.product.planning.domain.entity.ProductionBatch;
import com.product.planning.domain.vo.ProductionBatchVO;
import com.product.planning.mapper.ProductionBatchMapper;
import com.product.planning.service.IProductionBatchService;
import com.product.demand.api.DemandBatchQueryApi;
import com.product.demand.api.dto.AllocationCommand;
import com.product.demand.api.dto.DemandQueryRequests;
import com.product.demand.api.dto.OrderLineDTO;
import com.product.demand.api.dto.OrderDTO;
import com.product.masterdata.api.MasterDataBatchQueryApi;
import com.product.masterdata.api.dto.ProductBatchQueryRequest;
import com.product.masterdata.api.dto.ProductBatchResponse;
import com.product.masterdata.api.dto.ProductDTO;
import com.product.planning.domain.model.OrderLineSnapshot;
import com.product.planning.domain.model.OrderSnapshot;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生产批次（订单行拆批）Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2025-12-27
 */
@Slf4j
@Service
public class ProductionBatchServiceImpl extends ServiceImpl<ProductionBatchMapper, ProductionBatch> implements IProductionBatchService {
    @Autowired
    private ProductionBatchMapper productionBatchMapper;
    @Autowired
    private DemandBatchQueryApi demandBatchQueryApi;
    @Autowired
    private MasterDataBatchQueryApi masterDataBatchQueryApi;

    /**
     * 数量预占用命令（Phase 4：order_line 归 demand，单体 OrderLineAllocationMapper 的
     * allocateQty/releaseQty 同库更新改经 demand 内部契约执行；SQL 语义逐字一致，见
     * AllocationCommand javadoc）。写命令不重试；需求服务不可用时以 ServiceException
     * 拒绝（fail-closed），错误文案与单体同义（"订单行可用数量不足"由 updated==0 判定）。
     */
    private int allocateViaContract(Long orderLineId, long delta, String mode) {
        try {
            AllocationCommand.AllocationResponse response = demandBatchQueryApi.allocate(
                    new AllocationCommand(orderLineId, delta, mode));
            return response == null ? 0 : response.getUpdated();
        } catch (FeignException e) {
            log.error("订单行数量预占用失败(需求服务不可用): orderLineId={}, delta={}, mode={}, status={}",
                    orderLineId, delta, mode, e.status(), e);
            throw new ServiceException("需求服务不可用，无法预占用订单行数量，请稍后重试");
        }
    }

    /**
     * 查询生产批次（订单行拆批）
     *
     * @param batchId 生产批次（订单行拆批）主键
     * @return 生产批次（订单行拆批）
     */
    @Override
    public ProductionBatch selectProductionBatchByBatchId(Long batchId) {
        return getById(batchId);
    }

    /**
     * 查询生产批次（订单行拆批）列表
     *
     * @param productionBatch 查询条件
     * @return 生产批次（订单行拆批）集合
     */
    @Override
    public List<ProductionBatch> selectProductionBatchList(ProductionBatch productionBatch) {
        return list(buildQueryWrapper(productionBatch));
    }

    /**
     * 分页查询生产批次（订单行拆批）列表
     *
     * @param page           分页参数
     * @param batchSearchDTO 查询条件
     * @return 分页结果
     */
    /**
     * 分页查询批次（Phase 4 修复）：本库分页 + 跨域字段经批量契约填充。
     *
     * <p>聚合字段与单体 /pps/batch/list 逐字段一致（baselines §1.2 冻结）：
     * orderId/dueDate 来自需求域（批次 → 订单行 → 订单），productName 来自主数据域
     * （订单行 → 产品）。契约调用与行数无关：orderId 过滤时需求域 1 次（解析订单行）+
     * 每页 demand 2 次 + master-data 1 次，无 N+1。单体版本 LEFT JOIN 跨库表
     * （order_line/customer_order/product 在 planning_db 不存在，运行时报 1146），
     * 属拆库后的必然重写；契约不可用时跨域字段置空并告警（分页主数据为本库行，恒可读）。</p>
     */
    @Override
    public Page<ProductionBatchVO> selectProductionBatchPage(Page<ProductionBatchVO> page, BatchSearchDTO batchSearchDTO) {
        Long orderIdFilter = batchSearchDTO == null ? null : batchSearchDTO.getOrderId();
        List<Long> orderLineIds = null;
        if (orderIdFilter != null) {
            orderLineIds = resolveOrderLineIdsByOrderId(orderIdFilter);
            if (orderLineIds.isEmpty()) {
                return page;
            }
        }
        Page<ProductionBatchVO> result = productionBatchMapper.selectProductionBatchPage(page, batchSearchDTO, orderLineIds);
        enrichWithOrderAndProduct(result.getRecords());
        return result;
    }

    /** 经 demand 契约解析订单下的订单行 ID（orderId 过滤用；1 次契约调用）。 */
    private List<Long> resolveOrderLineIdsByOrderId(Long orderId) {
        try {
            OrderDTO.OrderLineBatchResponse response = demandBatchQueryApi.getOrderLines(
                    new DemandQueryRequests.OrderLineBatchQueryRequest(null));
            if (response == null || response.getOrderLines() == null) {
                throw new ServiceException("需求服务响应异常，无法解析订单下批次，请稍后重试");
            }
            return response.getOrderLines().stream()
                    .filter(line -> line != null && orderId.equals(line.getOrderId()))
                    .map(OrderLineDTO::getOrderLineId)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (FeignException e) {
            log.error("订单行解析失败(需求服务不可用): orderId={}, status={}", orderId, e.status(), e);
            throw new ServiceException("需求服务不可用，无法查询订单下批次，请稍后重试");
        }
    }

    /**
     * 跨域字段批量填充（每页各 1 次契约调用，与行数无关；填充字段与单体 join 结果一致）。
     */
    private void enrichWithOrderAndProduct(List<ProductionBatchVO> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        Set<Long> orderLineIds = records.stream()
                .map(ProductionBatchVO::getOrderLineId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, OrderLineSnapshot> lineMap = Map.of();
        if (!orderLineIds.isEmpty()) {
            try {
                OrderDTO.OrderLineBatchResponse response = demandBatchQueryApi.getOrderLines(
                        new DemandQueryRequests.OrderLineBatchQueryRequest(List.copyOf(orderLineIds)));
                if (response != null && response.getOrderLines() != null) {
                    lineMap = response.getOrderLines().stream()
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(OrderLineDTO::getOrderLineId, this::toSnapshot, (a, b) -> a));
                }
            } catch (FeignException e) {
                log.error("批次页订单行填充失败(需求服务不可用，跨域字段置空): status={}", e.status(), e);
            }
        }
        Set<Long> orderIds = lineMap.values().stream()
                .map(OrderLineSnapshot::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, OrderSnapshot> orderMap = Map.of();
        if (!orderIds.isEmpty()) {
            try {
                OrderDTO.OrderBatchResponse response = demandBatchQueryApi.getOrders(
                        new DemandQueryRequests.OrderBatchQueryRequest(List.copyOf(orderIds)));
                if (response != null && response.getOrders() != null) {
                    Map<Long, OrderSnapshot> collected = new java.util.HashMap<>();
                    response.getOrders().forEach(dto -> {
                        if (dto != null && dto.getOrderId() != null) {
                            collected.put(dto.getOrderId(), toOrderSnapshot(dto));
                        }
                    });
                    orderMap = collected;
                }
            } catch (FeignException e) {
                log.error("批次页订单填充失败(需求服务不可用，跨域字段置空): status={}", e.status(), e);
            }
        }
        Set<Long> productIds = lineMap.values().stream()
                .map(OrderLineSnapshot::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> productNames = Map.of();
        if (!productIds.isEmpty()) {
            try {
                ProductBatchResponse response = masterDataBatchQueryApi.getProducts(
                        new ProductBatchQueryRequest(List.copyOf(productIds)));
                if (response != null && response.getProducts() != null) {
                    productNames = response.getProducts().stream()
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(ProductDTO::getProductId, ProductDTO::getProductName, (a, b) -> a));
                }
            } catch (FeignException e) {
                log.error("批次页产品名填充失败(主数据服务不可用，跨域字段置空): status={}", e.status(), e);
            }
        }
        for (ProductionBatchVO vo : records) {
            OrderLineSnapshot line = vo.getOrderLineId() == null ? null : lineMap.get(vo.getOrderLineId());
            if (line != null) {
                vo.setOrderId(line.getOrderId());
                if (line.getProductId() != null) {
                    vo.setProductName(productNames.get(line.getProductId()));
                }
                OrderSnapshot order = line.getOrderId() == null ? null : orderMap.get(line.getOrderId());
                if (order != null) {
                    vo.setDueDate(order.getDueDate());
                }
            }
        }
    }

    private OrderLineSnapshot toSnapshot(OrderLineDTO dto) {
        OrderLineSnapshot snapshot = new OrderLineSnapshot();
        snapshot.setOrderLineId(dto.getOrderLineId());
        snapshot.setOrderId(dto.getOrderId());
        snapshot.setProductId(dto.getProductId());
        snapshot.setQty(dto.getQty());
        snapshot.setAllocatedQty(dto.getAllocatedQty());
        snapshot.setStatus(dto.getStatus());
        return snapshot;
    }

    private OrderSnapshot toOrderSnapshot(OrderDTO dto) {
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setOrderId(dto.getOrderId());
        snapshot.setDueDate(dto.getDueDate());
        snapshot.setPriority(dto.getPriority());
        snapshot.setStatus(dto.getStatus());
        return snapshot;
    }

    /**
     * 新增生产批次（订单行拆批）
     *
     * @param productionBatch 生产批次（订单行拆批）
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertProductionBatch(ProductionBatch productionBatch) {
        if (productionBatch == null || productionBatch.getOrderLineId() == null || productionBatch.getBatchQty() == null) {
            throw new ServiceException("订单行ID和批次数量不能为空");
        }
        if (productionBatch.getStatus() == null) {
            productionBatch.setStatus(StatusConstants.PLANNED_PRODUCTION_BATCH);
        }
        // Phase 4：先经 demand 契约预占数量，成功后本地落批次；本地失败则以负数 delta
        // 补偿释放（跨服务无同一事务，补偿失败时记录告警——窗口为"已预占无批次"，可经
        // 修改/删除批次或人工对账收敛；方向与单体"预占→落批"一致）。
        Long orderLineId = productionBatch.getOrderLineId();
        Long batchQty = productionBatch.getBatchQty();
        int affected = allocateViaContract(orderLineId, batchQty, AllocationCommand.MODE_ALLOCATE);
        if (affected != 1) {
            throw new ServiceException("订单行可用数量不足，无法拆批");
        }
        boolean saved = save(productionBatch);
        if (!saved) {
            releaseQuietly(orderLineId, batchQty);
            throw new ServiceException("创建生产批次失败");
        }
        return true;
    }

    /**
     * 批量新增生产批次（订单行拆批）
     *
     * @param productionBatchs 生产批次（订单行拆批）列表
     * @return 成功条数
     */
    @Override
    public int batchInsertProductionBatch(List<ProductionBatch> productionBatchs) {
        if (CollectionUtils.isEmpty(productionBatchs)) {
            return 0;
        }
        boolean success = saveBatch(productionBatchs);
        return success ? productionBatchs.size() : 0;
    }

    /**
     * 修改生产批次（订单行拆批）
     *
     * @param productionBatch 生产批次（订单行拆批）
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProductionBatch(ProductionBatch productionBatch) {
        if (productionBatch == null || productionBatch.getBatchId() == null || productionBatch.getBatchQty() == null) {
            throw new ServiceException("批次ID和批次数量不能为空");
        }
        // 进行sql行锁
        ProductionBatch locked = productionBatchMapper.selectBatchForUpdate(productionBatch.getBatchId());
        if (locked == null) {
            throw new ServiceException("生产批次不存在，无法修改");
        }
        Long orderLineId = locked.getOrderLineId();
        if (productionBatch.getOrderLineId() != null && !productionBatch.getOrderLineId().equals(orderLineId)) {
            throw new ServiceException("不允许修改来源订单行");
        }
        long oldQty = locked.getBatchQty() == null ? 0L : locked.getBatchQty();
        long newQty = productionBatch.getBatchQty();
        long delta = newQty - oldQty;
        if (delta > 0) {
            int affected = allocateViaContract(orderLineId, delta, AllocationCommand.MODE_ALLOCATE);
            if (affected != 1) {
                throw new ServiceException("订单行可用数量不足，无法增加批次数量");
            }
        } else if (delta < 0) {
            int affected = allocateViaContract(orderLineId, delta, AllocationCommand.MODE_RELEASE);
            if (affected != 1) {
                throw new ServiceException("释放订单行占用失败");
            }
        }
        productionBatch.setOrderLineId(orderLineId);
        return updateById(productionBatch);
    }

    /**
     * 批量删除生产批次（订单行拆批）
     *
     * @param batchIds 主键集合
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProductionBatchByBatchIds(Long[] batchIds) {
        if (batchIds == null || batchIds.length == 0) {
            return false;
        }
        for (Long batchId : batchIds) {
            deleteOneWithRelease(batchId);
        }
        return true;
    }

    /**
     * 删除生产批次（订单行拆批）信息
     *
     * @param batchId 主键
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProductionBatchByBatchId(Long batchId) {
        return deleteOneWithRelease(batchId);
    }

    @Override
    public boolean release(Long batchId) {
        ProductionBatch productionBatch = lambdaQuery()
                .select(ProductionBatch::getOrderLineId, ProductionBatch::getStatus)
                .eq(ProductionBatch::getBatchId, batchId)
                .last("limit 1").one();
        // Phase 4：order_line 归 demand，订单行状态经契约批量查询（1 次调用）。
        // 单体 orderLine 缺失时为 NPE（同库孤儿数据）；服务化后显式判定为"订单行不存在"
        // （显式差异：数据完好时行为与单体一致）。
        OrderLineSnapshot orderLine = loadOrderLineQuietly(productionBatch.getOrderLineId());
        String status = productionBatch.getStatus();
        if (orderLine == null || StatusConstants.NEW_ORDER_LINE.equals(orderLine.getStatus())) {
            throw new ServiceException("订单行未发布");
        }
        if (status.equals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次在执行中");
        }
        if (status.equals(StatusConstants.DONE_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次已完成");
        }
        return lambdaUpdate().set(ProductionBatch::getStatus, StatusConstants.RELEASED_PRODUCTION_BATCH)
                .eq(ProductionBatch::getBatchId, batchId)
                .update();
    }

    @Override
    public boolean cancelRelease(Long batchId) {
        ProductionBatch productionBatch = lambdaQuery()
                .select(ProductionBatch::getStatus)
                .eq(ProductionBatch::getBatchId, batchId)
                .last("limit 1").one();
        String status = productionBatch.getStatus();
        if (status.equals(StatusConstants.IN_PROCESS_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次在执行中");
        }
        if (status.equals(StatusConstants.DONE_PRODUCTION_BATCH)) {
            throw new ServiceException("该批次已完成");
        }
        return lambdaUpdate().set(ProductionBatch::getStatus, StatusConstants.PLANNED_PRODUCTION_BATCH)
                .eq(ProductionBatch::getBatchId, batchId)
                .update();
    }

    private boolean deleteOneWithRelease(Long batchId) {
        if (batchId == null) {
            return false;
        }
        // sql主键行锁
        ProductionBatch locked = productionBatchMapper.selectBatchForUpdate(batchId);
        if (locked == null) {
            return false;
        }
        Long orderLineId = locked.getOrderLineId();
        long batchQty = locked.getBatchQty() == null ? 0L : locked.getBatchQty();
        if (batchQty != 0L) {
            // 减去该批次数量（demand 契约；负数 delta 语义与单体一致）
            int affected = allocateViaContract(orderLineId, -batchQty, AllocationCommand.MODE_RELEASE);
            if (affected != 1) {
                throw new ServiceException("释放订单行占用失败");
            }
        }
        return removeById(batchId);
    }

    /** 按订单行 ID 经契约加载订单行快照（release 守卫用；1 次调用）。 */
    private OrderLineSnapshot loadOrderLineQuietly(Long orderLineId) {
        if (orderLineId == null) {
            return null;
        }
        try {
            OrderDTO.OrderLineBatchResponse response = demandBatchQueryApi.getOrderLines(
                    new DemandQueryRequests.OrderLineBatchQueryRequest(List.of(orderLineId)));
            if (response == null || response.getOrderLines() == null || response.getOrderLines().isEmpty()) {
                return null;
            }
            OrderLineDTO dto = response.getOrderLines().get(0);
            OrderLineSnapshot snapshot = new OrderLineSnapshot();
            snapshot.setOrderLineId(dto.getOrderLineId());
            snapshot.setOrderId(dto.getOrderId());
            snapshot.setProductId(dto.getProductId());
            snapshot.setQty(dto.getQty());
            snapshot.setAllocatedQty(dto.getAllocatedQty());
            snapshot.setStatus(dto.getStatus());
            return snapshot;
        } catch (FeignException e) {
            log.error("订单行状态查询失败(需求服务不可用): orderLineId={}, status={}", orderLineId, e.status(), e);
            throw new ServiceException("需求服务不可用，无法确认订单行状态，请稍后重试");
        }
    }

    /** 本地落批失败后的补偿释放（尽最大努力；失败仅告警，不掩盖原异常）。 */
    private void releaseQuietly(Long orderLineId, long batchQty) {
        try {
            allocateViaContract(orderLineId, -batchQty, AllocationCommand.MODE_RELEASE);
        } catch (Exception e) {
            log.error("创建生产批次失败的补偿释放未完成，需人工核对订单行预占: orderLineId={}, batchQty={}",
                    orderLineId, batchQty, e);
        }
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<ProductionBatch> buildQueryWrapper(ProductionBatch productionBatch) {
        LambdaQueryWrapper<ProductionBatch> wrapper = new LambdaQueryWrapper<>();
        if (productionBatch == null) {
            return wrapper;
        }
        wrapper.eq(productionBatch.getOrderLineId() != null, ProductionBatch::getOrderLineId, productionBatch.getOrderLineId());
        wrapper.eq(productionBatch.getBatchQty() != null, ProductionBatch::getBatchQty, productionBatch.getBatchQty());
        wrapper.eq(productionBatch.getStatus() != null, ProductionBatch::getStatus, productionBatch.getStatus());
        Object beginPlannedStart = productionBatch.getParams().get("beginPlannedStart");
        Object endPlannedStart = productionBatch.getParams().get("endPlannedStart");
        wrapper.between(beginPlannedStart != null && endPlannedStart != null, ProductionBatch::getPlannedStart, beginPlannedStart, endPlannedStart);
        Object beginPlannedEnd = productionBatch.getParams().get("beginPlannedEnd");
        Object endPlannedEnd = productionBatch.getParams().get("endPlannedEnd");
        wrapper.between(beginPlannedEnd != null && endPlannedEnd != null, ProductionBatch::getPlannedEnd, beginPlannedEnd, endPlannedEnd);
        return wrapper;
    }

}
