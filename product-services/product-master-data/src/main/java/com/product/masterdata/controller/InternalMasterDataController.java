package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.toolkit.Db;
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
import com.product.masterdata.api.dto.ProductExistenceResponse;
import com.product.masterdata.api.dto.ProductRouteDTO;
import com.product.masterdata.api.dto.ResourceBatchQueryRequest;
import com.product.masterdata.api.dto.ResourceBatchResponse;
import com.product.masterdata.api.dto.ResourceDTO;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.common.constant.ResourceConstants;
import com.product.masterdata.domain.entity.Calendar;
import com.product.masterdata.domain.entity.ChangeoverRule;
import com.product.masterdata.domain.entity.Fixture;
import com.product.masterdata.domain.entity.FixtureMoldCompatibility;
import com.product.masterdata.domain.entity.Machine;
import com.product.masterdata.domain.entity.MachineMoldCompatibility;
import com.product.masterdata.domain.entity.Mold;
import com.product.masterdata.domain.entity.Product;
import com.product.masterdata.domain.entity.ProductMoldParam;
import com.product.masterdata.domain.entity.ProductRoute;
import com.product.masterdata.domain.entity.Resource;
import com.product.masterdata.domain.entity.ResourceCapability;
import com.product.masterdata.domain.entity.RouteOperation;
import com.product.masterdata.service.MasterDataVersionService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主数据批量查询契约端点（product-master-data-api 的 MasterDataBatchQueryApi 实现）。
 *
 * <p>鉴权与其余端点一致：调用方透传用户 JWT，本地验签（ADR-0003 两层校验）；
 * 无有效 token 的内部调用一律 401（防伪造内部头）。超时/重试语义由消费方配置
 * （契约 javadoc 与 demand application.yml），本端点为只读查询。</p>
 */
@RestController
@RequestMapping("/internal/master-data")
public class InternalMasterDataController implements com.product.masterdata.api.MasterDataBatchQueryApi {

    private final MasterDataVersionService versionService;

    public InternalMasterDataController(MasterDataVersionService versionService) {
        this.versionService = versionService;
    }

    @Override
    public ProductExistenceResponse existsProducts(@RequestBody ProductBatchQueryRequest request) {
        requireIds(request == null ? null : request.getProductIds(), "产品ID集合不能为空");
        ProductBatchResponse batch = getProducts(request);
        ProductExistenceResponse response = new ProductExistenceResponse();
        response.setSnapshotVersion(batch.getSnapshotVersion());
        response.setExistingIds(batch.getProducts().stream()
                .map(ProductDTO::getProductId)
                .collect(Collectors.toList()));
        return response;
    }

    @Override
    public ProductBatchResponse getProducts(@RequestBody ProductBatchQueryRequest request) {
        List<Long> ids = request == null ? null : request.getProductIds();
        if (ids != null && !ids.isEmpty()) {
            requireIds(ids, "产品ID集合不能为空");
        }
        List<Product> products = Db.lambdaQuery(Product.class)
                .in(ids != null && !ids.isEmpty(), Product::getProductId, ids == null ? null : ids)
                .list();
        List<Long> productIds = products.stream()
                .map(Product::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Long, List<ProductMoldParam>> moldParamsByProduct = productIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(ProductMoldParam.class)
                        .in(ProductMoldParam::getProductId, productIds)
                        .list()
                        .stream()
                        .collect(Collectors.groupingBy(ProductMoldParam::getProductId));

        Map<Long, ProductRoute> activeRouteByProduct = loadActiveRoutes(productIds);

        ProductBatchResponse response = new ProductBatchResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        response.setProducts(products.stream()
                .map(product -> toProductDTO(product,
                        moldParamsByProduct.get(product.getProductId()),
                        activeRouteByProduct.get(product.getProductId())))
                .collect(Collectors.toList()));
        return response;
    }

    @Override
    public ResourceBatchResponse getResources(@RequestBody ResourceBatchQueryRequest request) {
        List<Long> ids = request == null ? null : request.getResourceIds();
        if (ids != null && !ids.isEmpty()) {
            requireIds(ids, "资源ID集合不能为空");
        }
        List<String> types = request == null ? null : request.getResourceTypes();
        List<Resource> resources = Db.lambdaQuery(Resource.class)
                .in(ids != null && !ids.isEmpty(), Resource::getResourceId, ids == null ? null : ids)
                .in(CollectionUtils.isNotEmpty(types), Resource::getResourceType, types)
                .list();
        List<Long> resourceIds = resources.stream()
                .map(Resource::getResourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Long, Machine> machineById = resourceIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(Machine.class)
                        .in(Machine::getMachineId, resourceIds)
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Machine::getMachineId, Function.identity(), (a, b) -> a));
        Map<Long, Mold> moldById = resourceIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(Mold.class)
                        .in(Mold::getMoldId, resourceIds)
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Mold::getMoldId, Function.identity(), (a, b) -> a));
        Map<Long, Fixture> fixtureById = resourceIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(Fixture.class)
                        .in(Fixture::getFixtureId, resourceIds)
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Fixture::getFixtureId, Function.identity(), (a, b) -> a));
        Map<Long, List<ResourceCapability>> capabilityByResource = resourceIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(ResourceCapability.class)
                        .in(ResourceCapability::getResourceId, resourceIds)
                        .list()
                        .stream()
                        .collect(Collectors.groupingBy(ResourceCapability::getResourceId));
        Map<Long, List<MachineMoldCompatibility>> compatibilityByMachine = resourceIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(MachineMoldCompatibility.class)
                        .in(MachineMoldCompatibility::getMachineId, resourceIds)
                        .list()
                        .stream()
                        .filter(item -> item != null && item.getMachineId() != null)
                        .collect(Collectors.groupingBy(MachineMoldCompatibility::getMachineId));
        // 夹具-模具兼容行（2026-09-15 增量）：与上方各扩展表同款一次 IN 批量查询（无 N+1），
        // 仅在 FIXTURE 资源的 fixture 扩展块内挂载（孤儿行脏数据不会泄漏到非夹具资源）
        Map<Long, List<FixtureMoldCompatibility>> fixtureCompatByFixture = resourceIds.isEmpty() ? Map.of()
                : Db.lambdaQuery(FixtureMoldCompatibility.class)
                        .in(FixtureMoldCompatibility::getFixtureId, resourceIds)
                        .list()
                        .stream()
                        .filter(item -> item != null && item.getFixtureId() != null)
                        .collect(Collectors.groupingBy(FixtureMoldCompatibility::getFixtureId));

        ResourceBatchResponse response = new ResourceBatchResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        response.setResources(resources.stream()
                .map(resource -> toResourceDTO(resource,
                        machineById.get(resource.getResourceId()),
                        moldById.get(resource.getResourceId()),
                        fixtureById.get(resource.getResourceId()),
                        compatibilityByMachine.getOrDefault(resource.getResourceId(), List.of()),
                        fixtureCompatByFixture.getOrDefault(resource.getResourceId(), List.of()),
                        capabilityByResource.get(resource.getResourceId())))
                .collect(Collectors.toList()));
        return response;
    }

    /**
     * 批量加载班次日历（Phase 4 契约端点；{@code calendarIds} 为 null/空时全量）。
     */
    @Override
    public CalendarBatchResponse getCalendars(@org.springframework.web.bind.annotation.RequestBody CalendarBatchQueryRequest request) {
        List<Long> ids = request == null ? null : request.getCalendarIds();
        if (ids != null && !ids.isEmpty()) {
            requireIds(ids, "日历ID集合不能为空");
        }
        List<Calendar> calendars = Db.lambdaQuery(Calendar.class)
                .in(ids != null && !ids.isEmpty(), Calendar::getCalendarId, ids == null ? null : ids)
                .list();
        CalendarBatchResponse response = new CalendarBatchResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        response.setCalendars(calendars.stream()
                .filter(Objects::nonNull)
                .map(calendar -> {
                    CalendarDTO dto = new CalendarDTO();
                    dto.setCalendarId(calendar.getCalendarId());
                    dto.setCalendarName(calendar.getCalendarName());
                    dto.setWorkdayPattern(calendar.getWorkdayPattern());
                    dto.setShiftStart(calendar.getShiftStart());
                    dto.setShiftEnd(calendar.getShiftEnd());
                    dto.setVersion(epochMillis(calendar.getUpdateTime()));
                    return dto;
                })
                .collect(Collectors.toList()));
        return response;
    }

    /**
     * 当前默认换型规则（Phase 4 契约端点；单体 changeover_rule limit 1 语义）。
     */
    @Override
    public ChangeoverRuleResponse getCurrentChangeoverRule() {
        ChangeoverRule rule = Db.lambdaQuery(ChangeoverRule.class)
                .last("limit 1")
                .one();
        ChangeoverRuleResponse response = new ChangeoverRuleResponse();
        response.setSnapshotVersion(versionService.currentVersion());
        if (rule != null) {
            ChangeoverRuleDTO dto = new ChangeoverRuleDTO();
            dto.setRuleId(rule.getRuleId());
            dto.setSameMoldTimeMin(rule.getSameMoldTimeMin());
            dto.setDifferentMoldTimeMin(rule.getDifferentMoldTimeMin());
            dto.setMaterialChangeExtraMin(rule.getMaterialChangeExtraMin());
            dto.setColorChangeExtraMin(rule.getColorChangeExtraMin());
            response.setRule(dto);
        }
        return response;
    }

    /**
     * 当前数据版本计数（Phase 4 轻量端点；排程快照漂移检测）。
     */
    @Override
    public DataVersionResponse getDataVersion() {
        return new DataVersionResponse(versionService.currentVersion());
    }

    private Map<Long, ProductRoute> loadActiveRoutes(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return Db.lambdaQuery(ProductRoute.class)
                .in(ProductRoute::getProductId, productIds)
                .eq(ProductRoute::getIsActive, 1)
                .list()
                .stream()
                .filter(route -> route.getProductId() != null)
                .collect(Collectors.toMap(ProductRoute::getProductId, Function.identity(), (a, b) -> a));
    }

    private ProductDTO toProductDTO(Product product, List<ProductMoldParam> moldParams, ProductRoute activeRoute) {
        ProductDTO dto = new ProductDTO();
        dto.setProductId(product.getProductId());
        dto.setProductName(product.getProductName());
        dto.setImage(product.getImage());
        dto.setMaterialCode(product.getMaterialCode());
        dto.setColorCode(product.getColorCode());
        dto.setVersion(epochMillis(product.getUpdateTime()));
        if (CollectionUtils.isNotEmpty(moldParams)) {
            dto.setMoldParams(moldParams.stream().map(param -> {
                ProductDTO.ProductMoldParamDTO paramDTO = new ProductDTO.ProductMoldParamDTO();
                paramDTO.setMoldId(param.getMoldId());
                paramDTO.setCycleTimeSec(param.getCycleTimeSec());
                paramDTO.setCavity(param.getCavity());
                paramDTO.setYieldRate(param.getYieldRate());
                paramDTO.setUtilization(param.getUtilization());
                return paramDTO;
            }).collect(Collectors.toList()));
        }
        if (activeRoute != null) {
            dto.setActiveRoute(toRouteDTO(activeRoute));
        }
        return dto;
    }

    private ProductRouteDTO toRouteDTO(ProductRoute route) {
        ProductRouteDTO dto = new ProductRouteDTO();
        dto.setRouteId(route.getRouteId());
        dto.setProductId(route.getProductId());
        dto.setRouteVersion(route.getVersion());
        dto.setIsActive(route.getIsActive());
        dto.setVersion(epochMillis(route.getUpdateTime()));
        List<RouteOperation> operations = Db.lambdaQuery(RouteOperation.class)
                .eq(RouteOperation::getRouteId, route.getRouteId())
                .list()
                .stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RouteOperation::getSequence, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(RouteOperation::getOpCode, Comparator.nullsLast(String::compareTo)))
                .toList();
        dto.setOperations(operations.stream().map(operation -> {
            ProductRouteDTO.RouteOperationDTO opDTO = new ProductRouteDTO.RouteOperationDTO();
            opDTO.setOpId(operation.getOpId());
            opDTO.setOpCode(operation.getOpCode());
            opDTO.setSequence(operation.getSequence());
            opDTO.setEligibleResourceRule(operation.getEligibleResourceRule());
            opDTO.setStdTimeModel(operation.getStdTimeModel());
            opDTO.setQueuePolicy(operation.getQueuePolicy());
            return opDTO;
        }).collect(Collectors.toList()));
        return dto;
    }

    private ResourceDTO toResourceDTO(Resource resource, Machine machine, Mold mold, Fixture fixture,
                                      List<MachineMoldCompatibility> compatibilities,
                                      List<FixtureMoldCompatibility> fixtureCompatibilities,
                                      List<ResourceCapability> capabilities) {
        ResourceDTO dto = new ResourceDTO();
        dto.setResourceId(resource.getResourceId());
        dto.setResourceType(resource.getResourceType());
        dto.setName(resource.getName());
        dto.setStatus(resource.getStatus());
        dto.setCalendarId(resource.getCalendarId());
        dto.setOrgUnit(resource.getOrgUnit());
        dto.setVersion(epochMillis(resource.getUpdateTime()));
        if (machine != null) {
            ResourceDTO.MachineDTO machineDTO = new ResourceDTO.MachineDTO();
            machineDTO.setMachineId(machine.getMachineId());
            machineDTO.setTonnage(machine.getTonnage());
            machineDTO.setDefaultSetupTimeMin(machine.getDefaultSetupTimeMin());
            dto.setMachine(machineDTO);
        }
        if (CollectionUtils.isNotEmpty(compatibilities)) {
            dto.setMoldCompatibilities(compatibilities.stream().map(compatibility -> {
                MachineMoldCompatibilityDTO compatibilityDTO = new MachineMoldCompatibilityDTO();
                compatibilityDTO.setMachineId(compatibility.getMachineId());
                compatibilityDTO.setMoldId(compatibility.getMoldId());
                compatibilityDTO.setIsCompatible(compatibility.getIsCompatible());
                return compatibilityDTO;
            }).collect(Collectors.toList()));
        }
        if (mold != null) {
            ResourceDTO.MoldDTO moldDTO = new ResourceDTO.MoldDTO();
            moldDTO.setMoldId(mold.getMoldId());
            moldDTO.setMoldCode(mold.getMoldCode());
            moldDTO.setCavity(mold.getCavity());
            moldDTO.setMoldStatus(mold.getMoldStatus());
            moldDTO.setNextMaintDue(mold.getNextMaintDue());
            dto.setMold(moldDTO);
        }
        // 夹具扩展仅对 FIXTURE 类型资源挂载（扩展行主键 = resource.resource_id；
        // 旧 master-data 响应/非夹具资源不含该字段，消费端按 null 处理即可）
        if (fixture != null && ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(resource.getResourceType())) {
            ResourceDTO.FixtureDTO fixtureDTO = new ResourceDTO.FixtureDTO();
            fixtureDTO.setFixtureId(fixture.getFixtureId());
            fixtureDTO.setFixtureCode(fixture.getFixtureCode());
            // 夹具-模具兼容行挂载方式与机台兼容一致：无兼容数据时保持 null（2026-09-15 增量）
            if (CollectionUtils.isNotEmpty(fixtureCompatibilities)) {
                fixtureDTO.setMoldCompatibilities(fixtureCompatibilities.stream().map(compatibility -> {
                    FixtureMoldCompatibilityDTO compatibilityDTO = new FixtureMoldCompatibilityDTO();
                    compatibilityDTO.setFixtureId(compatibility.getFixtureId());
                    compatibilityDTO.setMoldId(compatibility.getMoldId());
                    compatibilityDTO.setIsCompatible(compatibility.getIsCompatible());
                    return compatibilityDTO;
                }).collect(Collectors.toList()));
            }
            dto.setFixture(fixtureDTO);
        }
        if (CollectionUtils.isNotEmpty(capabilities)) {
            dto.setCapabilities(capabilities.stream().map(capability -> {
                ResourceDTO.CapabilityDTO capabilityDTO = new ResourceDTO.CapabilityDTO();
                capabilityDTO.setOpCode(capability.getOpCode());
                capabilityDTO.setProductId(capability.getProductId());
                capabilityDTO.setIsEnabled(capability.getIsEnabled());
                capabilityDTO.setPriorityWeight(capability.getPriorityWeight());
                return capabilityDTO;
            }).collect(Collectors.toList()));
        }
        return dto;
    }

    private long epochMillis(java.time.LocalDateTime time) {
        return time == null ? 0L : time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void requireIds(List<Long> ids, String emptyMessage) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(emptyMessage);
        }
        if (ids.size() > ProductBatchQueryRequest.MAX_IDS) {
            throw new ServiceException("批量查询ID数超限: " + ids.size() + " > " + ProductBatchQueryRequest.MAX_IDS);
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new ServiceException("批量查询ID存在重复");
        }
    }
}
