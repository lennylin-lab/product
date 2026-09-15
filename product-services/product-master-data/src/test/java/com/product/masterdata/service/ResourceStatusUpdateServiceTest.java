package com.product.masterdata.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.api.dto.ResourceStatusUpdateRequest;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.domain.entity.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源权威状态更新服务单测（KD3 回写契约；离线 Mockito，mock Db 静态链式查询/更新）：
 * 1) resourceId 必填 / toStatus 合法性 / 资源存在性校验（拒绝 → ServiceException）；
 * 2) 状态变化：更新 + 同事务 bump；
 * 3) 幂等语义：同状态重复回写静默成功（不更新、不重复 bump——版本计数只随主数据
 *    语义变化递增，避免在跑排程经漂移守卫无谓失败）。
 */
class ResourceStatusUpdateServiceTest {

    private MasterDataVersionService versionService;
    private ResourceStatusUpdateService service;

    @BeforeEach
    void setUp() {
        versionService = mock(MasterDataVersionService.class);
        service = new ResourceStatusUpdateService(versionService);
    }

    @Test
    void updateShouldRejectMissingResourceId() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            ResourceStatusUpdateRequest request = new ResourceStatusUpdateRequest();
            request.setToStatus("DOWN");

            ServiceException ex = assertThrows(ServiceException.class, () -> service.updateStatus(request));
            assertEquals("资源状态回写必须指定资源ID", ex.getMessage());
            verify(versionService, never()).bump();
        }
    }

    @Test
    void updateShouldRejectIllegalTargetStatus() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            for (String bad : new String[] {null, "", "BROKEN", "down"}) {
                ResourceStatusUpdateRequest request = request(301L, bad);
                ServiceException ex = assertThrows(ServiceException.class, () -> service.updateStatus(request),
                        "toStatus=" + bad + " 应被拒绝");
                assertEquals("资源状态回写目标状态非法: " + bad, ex.getMessage());
            }
            verify(versionService, never()).bump();
        }
    }

    @Test
    void updateShouldRejectUnknownResource() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubQuery(db, null);

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.updateStatus(request(9999L, "DOWN")));
            assertEquals("资源不存在: resourceId=9999", ex.getMessage());
            verify(versionService, never()).bump();
        }
    }

    @Test
    void updateShouldWriteStatusAndBumpInSameTransaction() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            Resource resource = resource(301L, "AVAILABLE");
            stubQuery(db, resource);
            LambdaUpdateChainWrapper updateWrapper = stubUpdate(db);

            when(versionService.currentVersion()).thenReturn(6L);
            ResourceStatusUpdateResponse response = service.updateStatus(request(301L, "DOWN"));

            // 状态变化：更新 + 同事务 bump（漂移守卫依赖同事务性）
            verify(updateWrapper).set(any(), eq("DOWN"));
            verify(updateWrapper).update();
            verify(versionService).bump();
            assertEquals(301L, response.getResourceId());
            assertEquals("DOWN", response.getStatus());
            assertEquals(6L, response.getSnapshotVersion());
        }
    }

    @Test
    void updateShouldBeIdempotentWhenStatusUnchanged() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            // 资源已是 DOWN，重复回写 DOWN（重放/重复消费场景）：静默成功、不更新、不 bump
            Resource resource = resource(301L, "DOWN");
            stubQuery(db, resource);
            when(versionService.currentVersion()).thenReturn(5L);

            ResourceStatusUpdateResponse response = service.updateStatus(request(301L, "DOWN"));

            db.verify(() -> Db.lambdaUpdate(Resource.class), never());
            verify(versionService, never()).bump();
            assertEquals(301L, response.getResourceId());
            assertEquals("DOWN", response.getStatus());
            assertEquals(5L, response.getSnapshotVersion());
        }
    }

    private ResourceStatusUpdateRequest request(Long resourceId, String toStatus) {
        ResourceStatusUpdateRequest request = new ResourceStatusUpdateRequest();
        request.setResourceId(resourceId);
        request.setToStatus(toStatus);
        request.setReasonCode("EQUIP_FAULT");
        return request;
    }

    private Resource resource(long resourceId, String status) {
        Resource resource = new Resource();
        resource.setResourceId(resourceId);
        resource.setResourceType("MACHINE");
        resource.setName("注塑机1");
        resource.setStatus(status);
        return resource;
    }

    private void stubQuery(MockedStatic<Db> db, Resource row) {
        LambdaQueryChainWrapper wrapper = mock(LambdaQueryChainWrapper.class,
                org.mockito.Mockito.withSettings().defaultAnswer(selfAnswer()));
        doReturn(row).when(wrapper).one();
        db.when(() -> Db.lambdaQuery(Resource.class)).thenReturn(wrapper);
    }

    private LambdaUpdateChainWrapper stubUpdate(MockedStatic<Db> db) {
        LambdaUpdateChainWrapper wrapper = mock(LambdaUpdateChainWrapper.class,
                org.mockito.Mockito.withSettings().defaultAnswer(selfAnswer()));
        doReturn(true).when(wrapper).update();
        db.when(() -> Db.lambdaUpdate(Resource.class)).thenReturn(wrapper);
        return wrapper;
    }

    /**
     * 链式条件方法（eq/set 等）经泛型桥接后返回类型可能擦除为 Object
     * （Mockito RETURNS_SELF 对 Object 返回值不生效），统一返回 mock 自身。
     */
    private static org.mockito.stubbing.Answer<Object> selfAnswer() {
        return invocation -> {
            Class<?> returnType = invocation.getMethod().getReturnType();
            Object self = invocation.getMock();
            return returnType == Object.class || returnType.isInstance(self) ? self : null;
        };
    }
}
