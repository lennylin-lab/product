package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.api.dto.ResourceBatchQueryRequest;
import com.product.masterdata.api.dto.ResourceBatchResponse;
import com.product.masterdata.api.dto.ResourceDTO;
import com.product.masterdata.domain.entity.Fixture;
import com.product.masterdata.domain.entity.Machine;
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
 * 资源批量契约 DTO 映射单测（2026-09-15 夹具建模；离线 Mockito，mock Db 静态链式查询）：
 * 1) FIXTURE 资源携带 fixture 扩展（fixtureId/fixtureCode）；
 * 2) 非 FIXTURE 资源即使存在孤儿扩展行也不携带 fixture 字段；
 * 3) FIXTURE 资源缺扩展行时 fixture 字段为 null（旧数据形态）；
 * 4) 机台扩展映射保持既有行为（契约向后兼容，旧消费者不受新字段影响）。
 */
class InternalMasterDataControllerResourceMappingTest {

    private MasterDataVersionService versionService;

    private InternalMasterDataController controller;

    @BeforeEach
    void setUp() {
        versionService = mock(MasterDataVersionService.class);
        when(versionService.currentVersion()).thenReturn(5L);
        controller = new InternalMasterDataController(versionService);
    }

    @Test
    void getResourcesShouldAttachFixtureExtensionOnlyForFixtureResource() {
        Resource fixtureResource = resource(301L, "FIXTURE", "夹具A");
        Resource machineResource = resource(101L, "MACHINE", "注塑机1");
        Fixture fixtureRow = new Fixture();
        fixtureRow.setFixtureId(301L);
        fixtureRow.setFixtureCode("FJ-001");
        // 孤儿扩展行：fixture_id 指向机台资源（脏数据防御，不应映射到机台 DTO）
        Fixture orphanRow = new Fixture();
        orphanRow.setFixtureId(101L);
        orphanRow.setFixtureCode("FJ-DIRTY");
        Machine machineRow = new Machine();
        machineRow.setMachineId(101L);
        machineRow.setTonnage(120);

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubQuery(db, Resource.class, List.of(fixtureResource, machineResource));
            stubQuery(db, Machine.class, List.of(machineRow));
            stubQuery(db, com.product.masterdata.domain.entity.Mold.class, List.of());
            stubQuery(db, Fixture.class, List.of(fixtureRow, orphanRow));
            stubQuery(db, com.product.masterdata.domain.entity.ResourceCapability.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.MachineMoldCompatibility.class, List.of());

            ResourceBatchQueryRequest request = new ResourceBatchQueryRequest(List.of(101L, 301L));
            ResourceBatchResponse response = controller.getResources(request);

            assertEquals(5L, response.getSnapshotVersion());
            assertEquals(2, response.getResources().size());

            ResourceDTO fixtureDto = findByResourceId(response, 301L);
            assertEquals("FIXTURE", fixtureDto.getResourceType());
            assertEquals(301L, fixtureDto.getFixture().getFixtureId());
            assertEquals("FJ-001", fixtureDto.getFixture().getFixtureCode());

            // 非 FIXTURE 资源不携带 fixture 字段；机台扩展保持既有映射
            ResourceDTO machineDto = findByResourceId(response, 101L);
            assertNull(machineDto.getFixture());
            assertEquals(120, machineDto.getMachine().getTonnage());
        }
    }

    @Test
    void getResourcesShouldOmitFixtureWhenExtensionRowMissing() {
        // FIXTURE 资源但无 fixture 扩展行 → fixture 字段为 null（旧数据形态，消费端按 null 处理）
        Resource fixtureResource = resource(302L, "FIXTURE", "夹具B");

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubQuery(db, Resource.class, List.of(fixtureResource));
            stubQuery(db, Machine.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.Mold.class, List.of());
            stubQuery(db, Fixture.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.ResourceCapability.class, List.of());
            stubQuery(db, com.product.masterdata.domain.entity.MachineMoldCompatibility.class, List.of());

            ResourceBatchQueryRequest request = new ResourceBatchQueryRequest(List.of(302L));
            ResourceBatchResponse response = controller.getResources(request);

            assertEquals(1, response.getResources().size());
            assertNull(response.getResources().get(0).getFixture());
            assertEquals("FIXTURE", response.getResources().get(0).getResourceType());
        }
    }

    /**
     * 打桩 Db.lambdaQuery(X.class) 链式查询：条件方法（in/eq 等）经泛型桥接后
     * 返回类型可能擦除为 Object（Mockito RETURNS_SELF 对 Object 返回值不生效），
     * 故用自定义 Answer 对链式方法统一返回 mock 自身，list() 显式打桩返回行数据。
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
}
