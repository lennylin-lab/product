package com.product.planning.service.impl;

import com.product.demand.api.DemandBatchQueryApi;
import com.product.demand.api.dto.AllocationCommand;
import com.product.demand.api.dto.DemandQueryRequests;
import com.product.demand.api.dto.OrderDTO;
import com.product.demand.api.dto.OrderLineDTO;
import com.product.masterdata.api.MasterDataBatchQueryApi;
import com.product.masterdata.api.dto.CalendarBatchQueryRequest;
import com.product.masterdata.api.dto.CalendarBatchResponse;
import com.product.masterdata.api.dto.CalendarDTO;
import com.product.masterdata.api.dto.ChangeoverRuleDTO;
import com.product.masterdata.api.dto.ChangeoverRuleResponse;
import com.product.masterdata.api.dto.DataVersionResponse;
import com.product.masterdata.api.dto.FixtureMoldCompatibilityDTO;
import com.product.masterdata.api.dto.MachineMoldCompatibilityDTO;
import com.product.masterdata.api.dto.ProductBatchQueryRequest;
import com.product.masterdata.api.dto.ProductBatchResponse;
import com.product.masterdata.api.dto.ProductDTO;
import com.product.masterdata.api.dto.ProductRouteDTO;
import com.product.masterdata.api.dto.ResourceBatchQueryRequest;
import com.product.masterdata.api.dto.ResourceBatchResponse;
import com.product.masterdata.api.dto.ResourceDTO;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.model.ChangeoverRule;
import com.product.planning.domain.model.Fixture;
import com.product.planning.domain.model.FixtureMoldCompatibility;
import com.product.planning.domain.model.Machine;
import com.product.planning.domain.model.MachineMoldCompatibility;
import com.product.planning.domain.model.OrderLineSnapshot;
import com.product.planning.domain.model.OrderSnapshot;
import com.product.planning.domain.model.Product;
import com.product.planning.domain.model.ProductMoldParam;
import com.product.planning.domain.model.RouteOperation;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.model.ResourceCapability;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 排程输入快照加载器（Phase 4，ADR-0004 §4 / design.md §4）。
 *
 * <p>单体排程在同库内直接读 order_line/customer_order（demand 权属）与 product/
 * product_route/route_operation/resource/machine/mold/machine_mold_compatibility/
 * resource_capability/calendar/changeover_rule（master-data 权属）。服务化后该读取
 * 全部改为批量契约（{@link DemandBatchQueryApi} / {@link MasterDataBatchQueryApi}）：
 * 每次排程运行的远程调用次数与任务数无关（基础 ≤10 次 + 按 MAX_IDS 的分块数，
 * 仍与任务数无关，issue #13），禁止算法循环内 N+1 RPC，禁止跨库访问。</p>
 *
 * <p><b>版本化快照规则（显式声明，替代单体同库读一致性）：</b></p>
 * <ol>
 *   <li>每份批量响应信封携带来源域 snapshotVersion（demand_data_version /
 *       master_data_data_version 单调计数）；</li>
 *   <li>加载基线：排程启动时 {@link #captureVersions()} 读取两域当前计数；</li>
 *   <li>落库前复核：计算完成后、事务提交前 {@link #verifyUnchanged(Versions)} 重新
 *       读取两域计数，任一变化即判定输入漂移 → 排程以显式异常失败（任务保持 READY，
 *       重新发起排程即重试），绝不部分落库（结果一致性事务边界见 Coordinator）；</li>
 *   <li>远程失败语义：契约超时/不可达/响应非法 → ServiceException（排程失败、任务
 *       保持 READY）；不做本地降级（快照不完整绝不排程）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulingSnapshotLoader {

    private final DemandBatchQueryApi demandBatchQueryApi;
    private final MasterDataBatchQueryApi masterDataBatchQueryApi;
    private final com.product.planning.config.ServiceIdentityTokenProvider serviceIdentityTokenProvider;

    /**
     * 契约调用 + 身份失效重试：提供方对认证失败返回单体逐字节契约（HTTP 200 +
     * code=401 错误体，反序列化后表现为响应体缺业务字段）。此时清除服务身份令牌
     * 缓存并重试一次（identity 重启换钥场景，读请求幂等安全）；仍失败才按响应异常处理。
     */
    private <T> T callWithAuthRetry(java.util.function.Supplier<T> call,
            java.util.function.Predicate<T> badShape) {
        T first = call.get();
        if (!badShape.test(first)) {
            return first;
        }
        serviceIdentityTokenProvider.evict();
        T second = call.get();
        if (!badShape.test(second)) {
            log.warn("service-identity token refreshed after rejection, contract call recovered");
            return second;
        }
        return second;
    }

    /** 契约单次批量 ID 上限（demand 与 master-data 同规则，issue #13 分块依据）。 */
    private static final int CONTRACT_MAX_IDS = DemandQueryRequests.MAX_IDS;

    /**
     * 按 {@link #CONTRACT_MAX_IDS} 分块执行批量契约并按 key 合并（issue #13：订单行
     * >1000 时单次调用被提供方 MAX_IDS 校验拒绝，整作业确定性 FAILED）。入参先去重
     * （提供方拒绝重复 ID），fetch 负责单分块的契约调用、认证重试与错误映射。
     */
    private <V> Map<Long, V> loadByIdsInChunks(Collection<Long> ids, String label,
            java.util.function.Function<List<Long>, List<V>> fetch,
            java.util.function.Function<V, Long> key) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, V> merged = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += CONTRACT_MAX_IDS) {
            List<Long> chunk = distinct.subList(from, Math.min(from + CONTRACT_MAX_IDS, distinct.size()));
            List<V> rows = fetch.apply(chunk);
            if (rows == null) {
                continue;
            }
            for (V row : rows) {
                if (row != null) {
                    merged.putIfAbsent(key.apply(row), row);
                }
            }
        }
        if (merged.size() < distinct.size()) {
            log.warn("{}批量加载部分ID无返回: expected={} found={}", label, distinct.size(), merged.size());
        }
        return merged;
    }

    // ========================== 版本基线 ==========================

    /** 两域版本基线（排程一次运行的快照一致性锚点）。 */
    public record Versions(long demandVersion, long masterDataVersion) {
    }

    /** 捕获加载基线：两域当前 data-version（轻量端点，各 1 次调用）。 */
    public Versions captureVersions() {
        return new Versions(currentDemandVersion(), currentMasterDataVersion());
    }

    /**
     * 落库前复核：两域 data-version 与加载基线一致才允许持久化（规则 3）。
     *
     * @throws ServiceException 输入漂移时（显式文案，任务保持 READY 可重试）
     */
    public void verifyUnchanged(Versions baseline) {
        if (baseline == null) {
            return;
        }
        long demandNow = currentDemandVersion();
        if (demandNow != baseline.demandVersion()) {
            throw new ServiceException("排程输入数据已变化(需求域版本漂移 " + baseline.demandVersion()
                    + "->" + demandNow + ")，本次排程已中止，请重新发起");
        }
        long masterDataNow = currentMasterDataVersion();
        if (masterDataNow != baseline.masterDataVersion()) {
            throw new ServiceException("排程输入数据已变化(主数据域版本漂移 " + baseline.masterDataVersion()
                    + "->" + masterDataNow + ")，本次排程已中止，请重新发起");
        }
    }

    private long currentDemandVersion() {
        try {
            AllocationCommand.DataVersionResponse response = demandBatchQueryApi.getDataVersion();
            return response == null ? 0L : response.getSnapshotVersion();
        } catch (FeignException e) {
            log.error("需求域版本查询失败，排程输入快照不可用: status={}", e.status(), e);
            throw new ServiceException("需求服务不可用，无法加载排程输入快照，请稍后重试");
        }
    }

    private long currentMasterDataVersion() {
        try {
            DataVersionResponse response = masterDataBatchQueryApi.getDataVersion();
            return response == null ? 0L : response.getSnapshotVersion();
        } catch (FeignException e) {
            log.error("主数据域版本查询失败，排程输入快照不可用: status={}", e.status(), e);
            throw new ServiceException("主数据服务不可用，无法加载排程输入快照，请稍后重试");
        }
    }

    // ========================== 需求域（demand_db） ==========================

    /** 按订单行 ID 批量加载订单行快照（按契约 MAX_IDS 分块）。 */
    public Map<Long, OrderLineSnapshot> loadOrderLines(Collection<Long> orderLineIds) {
        return loadByIdsInChunks(orderLineIds, "订单行", chunk -> {
            try {
                OrderDTO.OrderLineBatchResponse response = callWithAuthRetry(
                        () -> demandBatchQueryApi.getOrderLines(
                                new DemandQueryRequests.OrderLineBatchQueryRequest(chunk)),
                        r -> r == null || r.getOrderLines() == null);
                if (response == null || response.getOrderLines() == null) {
                    throw new ServiceException("需求服务响应异常，无法加载排程输入快照（请检查提供方日志与服务版本）");
                }
                return response.getOrderLines().stream()
                        .filter(Objects::nonNull)
                        .map(this::toOrderLineSnapshot)
                        .collect(Collectors.toList());
            } catch (FeignException e) {
                log.error("订单行批量加载失败，排程输入快照不可用: chunkSize={} status={}",
                        chunk.size(), e.status(), e);
                throw new ServiceException("需求服务不可用，无法加载排程输入快照，请稍后重试");
            }
        }, OrderLineSnapshot::getOrderLineId);
    }

    /** 按订单 ID 批量加载订单快照（交期/优先级，按契约 MAX_IDS 分块）。 */
    public Map<Long, OrderSnapshot> loadOrders(Collection<Long> orderIds) {
        return loadByIdsInChunks(orderIds, "订单", chunk -> {
            try {
                OrderDTO.OrderBatchResponse response = callWithAuthRetry(
                        () -> demandBatchQueryApi.getOrders(
                                new DemandQueryRequests.OrderBatchQueryRequest(chunk)),
                        r -> r == null || r.getOrders() == null);
                if (response == null || response.getOrders() == null) {
                    throw new ServiceException("需求服务响应异常，无法加载排程输入快照（请检查提供方日志与服务版本）");
                }
                return response.getOrders().stream()
                        .filter(Objects::nonNull)
                        .map(order -> {
                            OrderSnapshot snapshot = new OrderSnapshot();
                            snapshot.setOrderId(order.getOrderId());
                            snapshot.setDueDate(order.getDueDate());
                            snapshot.setPriority(order.getPriority());
                            snapshot.setStatus(order.getStatus());
                            return snapshot;
                        })
                        .collect(Collectors.toList());
            } catch (FeignException e) {
                log.error("订单批量加载失败，排程输入快照不可用: chunkSize={} status={}",
                        chunk.size(), e.status(), e);
                throw new ServiceException("需求服务不可用，无法加载排程输入快照，请稍后重试");
            }
        }, OrderSnapshot::getOrderId);
    }

    private OrderLineSnapshot toOrderLineSnapshot(OrderLineDTO dto) {
        OrderLineSnapshot snapshot = new OrderLineSnapshot();
        snapshot.setOrderLineId(dto.getOrderLineId());
        snapshot.setOrderId(dto.getOrderId());
        snapshot.setProductId(dto.getProductId());
        snapshot.setQty(dto.getQty());
        snapshot.setAllocatedQty(dto.getAllocatedQty());
        snapshot.setStatus(dto.getStatus());
        return snapshot;
    }

    // ========================== 主数据域（master_data_db） ==========================

    /**
     * 按类型批量加载可用（AVAILABLE）资源并挂载机台扩展/兼容矩阵/能力矩阵
     * （1 次契约调用；状态过滤与单体 loadAvailableResources 一致，内存执行）。
     */
    public List<Resource> loadAvailableResources(Collection<String> resourceTypes) {
        ResourceBatchResponse response;
        try {
            ResourceBatchQueryRequest request = new ResourceBatchQueryRequest();
            request.setResourceTypes(List.copyOf(resourceTypes));
            response = callWithAuthRetry(() -> masterDataBatchQueryApi.getResources(request),
                    r -> r == null);
        } catch (FeignException e) {
            log.error("资源批量加载失败，排程输入快照不可用: types={} status={}", resourceTypes, e.status(), e);
            throw new ServiceException("主数据服务不可用，无法加载排程输入快照，请稍后重试");
        }
        if (response == null) {
            throw new ServiceException("主数据服务响应异常，无法加载排程输入快照，请稍后重试");
        }
        List<ResourceDTO> dtos = response.getResources() == null ? List.of() : response.getResources();
        return dtos.stream()
                .filter(Objects::nonNull)
                .filter(item -> com.product.planning.common.constant.StatusConstants.AVAILABLE_RESOURCE_STATUS
                        .equals(item.getStatus()))
                .map(this::toResource)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** 批量加载资源关联班次日历（按契约 MAX_IDS 分块；ids 为空返回空表）。 */
    public Map<Long, Calendar> loadCalendars(Collection<Long> calendarIds) {
        Map<Long, Calendar> merged = loadByIdsInChunks(calendarIds, "日历", chunk -> {
            try {
                CalendarBatchResponse response = callWithAuthRetry(
                        () -> masterDataBatchQueryApi.getCalendars(
                                new CalendarBatchQueryRequest(chunk)),
                        r -> r == null || r.getCalendars() == null);
                if (response == null || response.getCalendars() == null) {
                    throw new ServiceException("主数据服务响应异常，无法加载排程输入快照（请检查提供方日志与服务版本）");
                }
                return response.getCalendars().stream()
                        .filter(Objects::nonNull)
                        .map(this::toCalendar)
                        .collect(Collectors.toList());
            } catch (FeignException e) {
                log.error("日历批量加载失败，排程输入快照不可用: chunkSize={} status={}",
                        chunk.size(), e.status(), e);
                throw new ServiceException("主数据服务不可用，无法加载排程输入快照，请稍后重试");
            }
        }, Calendar::getCalendarId);
        return new HashMap<>(merged);
    }

    /** 当前默认换型规则（1 次契约调用；单体 changeover_rule limit 1 语义，空表返回 null）。 */
    public ChangeoverRule loadDefaultChangeoverRule() {
        ChangeoverRuleResponse response;
        try {
            response = callWithAuthRetry(() -> masterDataBatchQueryApi.getCurrentChangeoverRule(),
                    r -> false);
        } catch (FeignException e) {
            log.error("换型规则加载失败，排程输入快照不可用: status={}", e.status(), e);
            throw new ServiceException("主数据服务不可用，无法加载排程输入快照，请稍后重试");
        }
        if (response == null || response.getRule() == null) {
            return null;
        }
        ChangeoverRuleDTO rule = response.getRule();
        ChangeoverRule model = new ChangeoverRule();
        model.setRuleId(rule.getRuleId());
        model.setSameMoldTimeMin(rule.getSameMoldTimeMin());
        model.setDifferentMoldTimeMin(rule.getDifferentMoldTimeMin());
        model.setMaterialChangeExtraMin(rule.getMaterialChangeExtraMin());
        model.setColorChangeExtraMin(rule.getColorChangeExtraMin());
        return model;
    }

    /** 按产品 ID 批量加载产品（材料/颜色编码 + 模具参数 + 启用路线工序，按契约 MAX_IDS 分块）。 */
    public Map<Long, Product> loadProducts(Collection<Long> productIds) {
        return loadByIdsInChunks(productIds, "产品", chunk -> {
            try {
                ProductBatchResponse response = callWithAuthRetry(
                        () -> masterDataBatchQueryApi.getProducts(
                                new ProductBatchQueryRequest(chunk)),
                        r -> r == null || r.getProducts() == null);
                if (response == null || response.getProducts() == null) {
                    throw new ServiceException("主数据服务响应异常，无法加载排程输入快照（请检查提供方日志与服务版本）");
                }
                return response.getProducts().stream()
                        .filter(Objects::nonNull)
                        .map(this::toProduct)
                        .collect(Collectors.toList());
            } catch (FeignException e) {
                log.error("产品批量加载失败，排程输入快照不可用: chunkSize={} status={}",
                        chunk.size(), e.status(), e);
                throw new ServiceException("主数据服务不可用，无法加载排程输入快照，请稍后重试");
            }
        }, Product::getProductId);
    }

    // ========================== DTO → 排程模型映射 ==========================

    private Resource toResource(ResourceDTO dto) {
        Resource resource = new Resource();
        resource.setResourceId(dto.getResourceId());
        resource.setResourceType(dto.getResourceType());
        resource.setName(dto.getName());
        resource.setStatus(dto.getStatus());
        resource.setCalendarId(dto.getCalendarId());
        if (dto.getMachine() != null) {
            Machine machine = new Machine();
            machine.setMachineId(dto.getMachine().getMachineId());
            machine.setTonnage(dto.getMachine().getTonnage());
            machine.setDefaultSetupTimeMin(dto.getMachine().getDefaultSetupTimeMin());
            List<MachineMoldCompatibility> compatibilities = dto.getMoldCompatibilities() == null
                    ? new ArrayList<>()
                    : dto.getMoldCompatibilities().stream()
                            .filter(Objects::nonNull)
                            .map(this::toCompatibility)
                            .collect(Collectors.toCollection(ArrayList::new));
            machine.setMoldCompatibilityList(compatibilities);
            resource.setMachine(machine);
        }
        // 夹具扩展（2026-09-15 增量）：dto.fixture 为 null（旧 master-data 或非夹具资源）时
        // Resource.fixture 保持 null，非夹具路径零变化；AVAILABLE 过滤/版本漂移守卫不特判夹具
        if (dto.getFixture() != null) {
            Fixture fixture = new Fixture();
            fixture.setFixtureId(dto.getFixture().getFixtureId());
            fixture.setFixtureCode(dto.getFixture().getFixtureCode());
            // 夹具-模具兼容行（2026-09-15 夹具兼容增量）：随同一 resources/batch 契约响应
            // 映射进内存模型；缺失（null）时映射为空表——不可满足裁决在排程计算器（child-3）
            fixture.setMoldCompatibilityList(dto.getFixture().getMoldCompatibilities() == null
                    ? new ArrayList<>()
                    : dto.getFixture().getMoldCompatibilities().stream()
                            .filter(Objects::nonNull)
                            .map(this::toFixtureCompatibility)
                            .collect(Collectors.toCollection(ArrayList::new)));
            resource.setFixture(fixture);
        }
        List<ResourceCapability> capabilities = dto.getCapabilities() == null
                ? new ArrayList<>()
                : dto.getCapabilities().stream()
                        .filter(Objects::nonNull)
                        .map(capability -> toCapability(resource.getResourceId(), capability))
                        .collect(Collectors.toCollection(ArrayList::new));
        resource.setCapabilityList(capabilities);
        return resource;
    }

    private MachineMoldCompatibility toCompatibility(MachineMoldCompatibilityDTO dto) {
        MachineMoldCompatibility model = new MachineMoldCompatibility();
        model.setMachineId(dto.getMachineId());
        model.setMoldId(dto.getMoldId());
        model.setIsCompatible(dto.getIsCompatible());
        return model;
    }

    /** 夹具-模具兼容行映射（2026-09-15 夹具兼容增量，与机台兼容同款纯内存映射）。 */
    private FixtureMoldCompatibility toFixtureCompatibility(FixtureMoldCompatibilityDTO dto) {
        FixtureMoldCompatibility model = new FixtureMoldCompatibility();
        model.setFixtureId(dto.getFixtureId());
        model.setMoldId(dto.getMoldId());
        model.setIsCompatible(dto.getIsCompatible());
        return model;
    }

    private ResourceCapability toCapability(Long resourceId, ResourceDTO.CapabilityDTO capability) {
        ResourceCapability model = new ResourceCapability();
        model.setResourceId(resourceId);
        model.setOpCode(capability.getOpCode());
        model.setProductId(capability.getProductId());
        model.setIsEnabled(capability.getIsEnabled());
        return model;
    }

    private Calendar toCalendar(CalendarDTO dto) {
        Calendar model = new Calendar();
        model.setCalendarId(dto.getCalendarId());
        model.setCalendarName(dto.getCalendarName());
        model.setWorkdayPattern(dto.getWorkdayPattern());
        model.setShiftStart(dto.getShiftStart());
        model.setShiftEnd(dto.getShiftEnd());
        return model;
    }

    private Product toProduct(ProductDTO dto) {
        Product model = new Product();
        model.setProductId(dto.getProductId());
        model.setProductName(dto.getProductName());
        model.setMaterialCode(dto.getMaterialCode());
        model.setColorCode(dto.getColorCode());
        model.setMoldParams(dto.getMoldParams() == null ? new ArrayList<>() : dto.getMoldParams().stream()
                .filter(Objects::nonNull)
                .map(this::toMoldParam)
                .collect(Collectors.toCollection(ArrayList::new)));
        model.setActiveOperations(extractActiveOperations(dto));
        return model;
    }

    /**
     * 提取启用路线的工序定义（sequence 升序 → opCode 升序，与单体
     * ProductRouteQueryService.loadActiveRouteOperationsByProductIds 排序一致）。
     */
    private List<RouteOperation> extractActiveOperations(ProductDTO dto) {
        ProductRouteDTO route = dto == null ? null : dto.getActiveRoute();
        if (route == null || route.getOperations() == null) {
            return new ArrayList<>();
        }
        return route.getOperations().stream()
                .filter(Objects::nonNull)
                .map(op -> {
                    RouteOperation model = new RouteOperation();
                    model.setOpId(op.getOpId());
                    model.setRouteId(route.getRouteId());
                    model.setOpCode(op.getOpCode());
                    model.setSequence(op.getSequence());
                    model.setEligibleResourceRule(op.getEligibleResourceRule());
                    model.setStdTimeModel(op.getStdTimeModel());
                    model.setQueuePolicy(op.getQueuePolicy());
                    return model;
                })
                .sorted(java.util.Comparator
                        .comparing(RouteOperation::getSequence, java.util.Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(RouteOperation::getOpCode, java.util.Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ProductMoldParam toMoldParam(ProductDTO.ProductMoldParamDTO dto) {
        ProductMoldParam model = new ProductMoldParam();
        model.setMoldId(dto.getMoldId());
        model.setCycleTimeSec(dto.getCycleTimeSec());
        model.setCavity(dto.getCavity());
        model.setYieldRate(dto.getYieldRate());
        model.setUtilization(dto.getUtilization());
        return model;
    }
}
