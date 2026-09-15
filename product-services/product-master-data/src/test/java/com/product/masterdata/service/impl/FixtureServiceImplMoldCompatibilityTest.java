package com.product.masterdata.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.domain.entity.FixtureMoldCompatibility;
import com.product.masterdata.service.MasterDataVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

/**
 * 夹具-模具兼容行维护单测（2026-09-15 夹具兼容任务移交；离线 Mockito，mock Db 静态链，
 * 沿用 {@code InternalMasterDataControllerFixtureCompatibilityTest} 风格）。
 *
 * <p>事务写/bump 语义（machine 扩展写路径同款约定）：saveMoldCompatibilities 成功后同事务
 * bump 递增版本计数；任一写失败（remove=false / saveBatch 抛异常，生产中由事务回滚兜底）
 * 不递增；fixtureId 为空的脏入参直接拒绝；mold_id 为空的脏行过滤不落库且统一盖章 fixtureId。</p>
 */
class FixtureServiceImplMoldCompatibilityTest {

    private MasterDataVersionService versionService;

    private FixtureServiceImpl service;

    @BeforeEach
    void setUp() {
        versionService = mock(MasterDataVersionService.class);
        service = new FixtureServiceImpl(versionService);
    }

    /** 成功：先删后插全量替换，脏行（mold_id 为空）被过滤，行统一盖章 fixtureId，成功后 bump 递增版本 */
    @Test
    void saveMoldCompatibilitiesShouldReplaceRowsAndBumpVersionOnSuccess() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubRemove(db, true);
            ArgumentCaptor<List<FixtureMoldCompatibility>> captor = ArgumentCaptor.forClass(List.class);
            db.when(() -> Db.saveBatch(captor.capture())).thenReturn(true);

            boolean done = service.saveMoldCompatibilities(301L, List.of(
                    compat(null, 201L, 1),
                    compat(null, null, 1),
                    compat(null, 202L, 0)));

            assertTrue(done);
            List<FixtureMoldCompatibility> saved = captor.getValue();
            assertEquals(2, saved.size());
            assertEquals(201L, saved.get(0).getMoldId());
            assertEquals(301L, saved.get(0).getFixtureId());
            assertEquals(202L, saved.get(1).getMoldId());
            assertEquals(301L, saved.get(1).getFixtureId());
            verify(versionService, times(1)).bump();
        }
    }

    /** 写失败（saveBatch 抛异常，生产中事务回滚）：不递增版本计数 */
    @Test
    void saveMoldCompatibilitiesShouldNotBumpVersionWhenSaveFails() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubRemove(db, true);
            db.when(() -> Db.saveBatch(anyList())).thenThrow(new RuntimeException("db write failed"));

            assertThrows(RuntimeException.class,
                    () -> service.saveMoldCompatibilities(301L, List.of(compat(null, 201L, 1))));

            verify(versionService, never()).bump();
        }
    }

    /** 删除旧行失败：整体失败返回 false，不递增版本计数 */
    @Test
    void saveMoldCompatibilitiesShouldNotBumpVersionWhenRemoveFails() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            stubRemove(db, false);
            db.when(() -> Db.saveBatch(anyList())).thenReturn(true);

            assertFalse(service.saveMoldCompatibilities(301L, List.of(compat(null, 201L, 1))));

            verify(versionService, never()).bump();
        }
    }

    /** fixtureId 为空的脏入参直接拒绝：不触碰数据与版本计数 */
    @Test
    void saveMoldCompatibilitiesShouldRejectNullFixtureId() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            assertFalse(service.saveMoldCompatibilities(null, List.of(compat(null, 201L, 1))));

            db.verifyNoInteractions();
            verifyNoInteractions(versionService);
        }
    }

    /**
     * 打桩 Db.lambdaUpdate(X.class) 链式更新（与既有映射测试同款：条件方法返回 mock 自身，
     * remove() 显式打桩返回删除结果）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubRemove(MockedStatic<Db> db, boolean removed) {
        LambdaUpdateChainWrapper wrapper = mock(LambdaUpdateChainWrapper.class,
                withSettings().defaultAnswer(invocation -> {
                    Class<?> returnType = invocation.getMethod().getReturnType();
                    Object self = invocation.getMock();
                    return returnType == Object.class || returnType.isInstance(self) ? self : null;
                }));
        doReturn(removed).when(wrapper).remove();
        db.when(() -> Db.lambdaUpdate(FixtureMoldCompatibility.class)).thenReturn(wrapper);
    }

    private FixtureMoldCompatibility compat(Long fixtureId, Long moldId, int isCompatible) {
        FixtureMoldCompatibility row = new FixtureMoldCompatibility();
        row.setFixtureId(fixtureId);
        row.setMoldId(moldId);
        row.setIsCompatible(isCompatible);
        return row;
    }
}
