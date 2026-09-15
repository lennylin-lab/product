package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.api.dto.ResourceBatchQueryRequest;
import com.product.masterdata.api.dto.ResourceBatchResponse;
import com.product.masterdata.api.dto.ResourceDTO;
import com.product.masterdata.domain.entity.Fixture;
import com.product.masterdata.domain.entity.FixtureMoldCompatibility;
import com.product.masterdata.domain.entity.Resource;
import com.product.masterdata.service.MasterDataVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 夹具-模具兼容行批量契约映射单测（2026-09-15 夹具兼容；离线 Mockito，mock Db 静态链式查询，
 * 沿用 {@link InternalMasterDataControllerResourceMappingTest} 风格）：
 * 1) FIXTURE 资源的 fixture 扩展携带 moldCompatibilities（fixtureId/moldId/isCompatible）；
 * 2) 无兼容行时 moldCompatibilities 保持 null（与机台兼容行下发方式一致）；
 * 3) 孤儿防御：fixture_id 指向非 FIXTURE 资源的兼容行不泄漏到该资源 DTO。
 */
class InternalMasterDataControllerFixtureCompatibilityTest {

    private MasterDataVersionService versionService;

    private InternalMasterDataController controller;

    @BeforeEach
    void setUp() {
        versionService = mock(MasterDataVersionService.class);
        when(versionService.currentVersion()).thenReturn(7L);
        controller = new InternalMasterDataController(versionService);
    }

    @Test
    void getResourcesShouldAttachMoldCompatibilitiesToFixtureDTO() {
        Resource fixtureResource = resource(301L, "FIXTURE", "夹具A");
        Fixture fixtureRow = fixture(301L, "FJ-001");
        FixtureMoldCompatibility compatible = compat(301L, 201L, 1);
        FixtureMoldCompatibility incompatible = compat(301L, 202L, 0);

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubQuery(db, Resource.class, List.of(fixtureResource));
            stubQuery(db, com.product.masterdata.domain.entity.Machine.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.Mold.class, List.of());
            stubQuery(db, Fixture.class, List.of(fixtureRow));
            stubQuery(db, com.product.masterdata.domain.entity.ResourceCapability.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.MachineMoldCompatibility.class, List.of());
            stubQuery(db, FixtureMoldCompatibility.class, List.of(compatible, incompatible));

            ResourceBatchResponse response = controller.getResources(new ResourceBatchQueryRequest(List.of(301L)));

            assertEquals(7L, response.getSnapshotVersion());
            ResourceDTO.FixtureDTO fixtureDTO = response.getResources().get(0).getFixture();
            assertEquals(301L, fixtureDTO.getFixtureId());
            assertEquals(2, fixtureDTO.getMoldCompatibilities().size());
            assertEquals(201L, fixtureDTO.getMoldCompatibilities().get(0).getMoldId());
            assertEquals(301L, fixtureDTO.getMoldCompatibilities().get(0).getFixtureId());
            assertEquals(1, fixtureDTO.getMoldCompatibilities().get(0).getIsCompatible());
            assertEquals(202L, fixtureDTO.getMoldCompatibilities().get(1).getMoldId());
            assertEquals(0, fixtureDTO.getMoldCompatibilities().get(1).getIsCompatible());
        }
    }

    @Test
    void getResourcesShouldKeepMoldCompatibilitiesNullWithoutCompatRows() {
        // 无兼容行 → fixture.moldCompatibilities 为 null（与机台兼容行的现状下发方式一致）
        Resource fixtureResource = resource(303L, "FIXTURE", "夹具C");

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubQuery(db, Resource.class, List.of(fixtureResource));
            stubQuery(db, com.product.masterdata.domain.entity.Machine.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.Mold.class, List.of());
            stubQuery(db, Fixture.class, List.of(fixture(303L, "FJ-003")));
            stubQuery(db, com.product.masterdata.domain.entity.ResourceCapability.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.MachineMoldCompatibility.class, List.of());
            stubQuery(db, FixtureMoldCompatibility.class, List.of());

            ResourceBatchResponse response = controller.getResources(new ResourceBatchQueryRequest(List.of(303L)));

            ResourceDTO.FixtureDTO fixtureDTO = response.getResources().get(0).getFixture();
            assertEquals(303L, fixtureDTO.getFixtureId());
            assertNull(fixtureDTO.getMoldCompatibilities());
        }
    }

    @Test
    void getResourcesShouldNotLeakCompatRowsToNonFixtureResource() {
        // 孤儿兼容行：fixture_id 指向机台资源（脏数据防御，不应映射到机台 DTO）；
        // 夹具资源自身的兼容行正常挂载
        Resource machineResource = resource(101L, "MACHINE", "注塑机1");
        Resource fixtureResource = resource(301L, "FIXTURE", "夹具A");
        FixtureMoldCompatibility orphanRow = compat(101L, 201L, 1);
        FixtureMoldCompatibility ownRow = compat(301L, 202L, 1);

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubQuery(db, Resource.class, List.of(machineResource, fixtureResource));
            stubQuery(db, com.product.masterdata.domain.entity.Machine.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.Mold.class, List.of());
            stubQuery(db, Fixture.class, List.of(fixture(301L, "FJ-001")));
            stubQuery(db, com.product.masterdata.domain.entity.ResourceCapability.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.MachineMoldCompatibility.class, List.of());
            stubQuery(db, FixtureMoldCompatibility.class, List.of(orphanRow, ownRow));

            ResourceBatchResponse response = controller.getResources(new ResourceBatchQueryRequest(List.of(101L, 301L)));

            ResourceDTO machineDto = findByResourceId(response, 101L);
            assertNull(machineDto.getFixture());
            assertNull(machineDto.getMoldCompatibilities());

            ResourceDTO fixtureDto = findByResourceId(response, 301L);
            assertEquals(1, fixtureDto.getFixture().getMoldCompatibilities().size());
            assertEquals(202L, fixtureDto.getFixture().getMoldCompatibilities().get(0).getMoldId());
        }
    }

    /**
     * 打桩 Db.lambdaQuery(X.class) 链式查询（与既有映射测试同款：条件方法返回 mock 自身，
     * list() 显式打桩返回行数据）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> void stubQuery(MockedStatic<Db> db, Class<T> entityClass, List<T> rows) {
        LambdaQueryChainWrapper wrapper = mock(LambdaQueryChainWrapper.class,
                org.mockito.Mockito.withSettings().defaultAnswer(invocation -> {
                    Class<?> returnType = invocation.getMethod().getReturnType();
                    Object self = invocation.getMock();
                    return returnType == Object.class || returnType.isInstance(self) ? self : null;
                }));
        doReturn(rows).when(wrapper).list();
        db.when(() -> Db.lambdaQuery(entityClass)).thenReturn(wrapper);
    }

    private ResourceDTO findByResourceId(ResourceBatchResponse response, long resourceId) {
        return response.getResources().stream()
                .filter(item -> item != null && item.getResourceId() == resourceId)
                .findFirst()
                .orElseThrow();
    }

    private Resource resource(long resourceId, String resourceType, String name) {
        Resource resource = new Resource();
        resource.setResourceId(resourceId);
        resource.setResourceType(resourceType);
        resource.setName(name);
        resource.setStatus("AVAILABLE");
        return resource;
    }

    private Fixture fixture(long fixtureId, String fixtureCode) {
        Fixture fixture = new Fixture();
        fixture.setFixtureId(fixtureId);
        fixture.setFixtureCode(fixtureCode);
        return fixture;
    }

    private FixtureMoldCompatibility compat(long fixtureId, long moldId, int isCompatible) {
        FixtureMoldCompatibility row = new FixtureMoldCompatibility();
        row.setFixtureId(fixtureId);
        row.setMoldId(moldId);
        row.setIsCompatible(isCompatible);
        return row;
    }
}
