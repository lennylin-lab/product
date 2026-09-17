package com.product.masterdata.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.domain.entity.Machine;
import com.product.masterdata.domain.entity.Resource;
import com.product.masterdata.service.MasterDataVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 删除机台服务单测（issue #8 离线 Mockito，约定对齐 ResourceStatusUpdateServiceTest）：
 * 1) 单/批量删除 machine 表无行 → 抛"机台不存在"，不触碰 resource 表、不 bump；
 * 2) 存在校验通过 → machine + resource 两表均删、同事务 bump；
 * 3) 批量任一缺失 → 整批拒绝（不做任何删除），报文含缺失 id；
 * 4) 空/null 数组 → 维持返回 false，无任何副作用。
 */
class MachineServiceImplDeleteTest {

    private MasterDataVersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = mock(MasterDataVersionService.class);
    }

    @Test
    void deleteSingleShouldRejectUnknownMachineWithoutTouchingResource() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            RecordingMachineService service = service(List.of());

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.deleteMachineByMachineId(201L));
            assertEquals("机台不存在: [201]", ex.getMessage());
            assertTrue(service.removedById.isEmpty());
            db.verify(() -> Db.removeById(anyLong(), eq(Resource.class)), never());
            verify(versionService, never()).bump();
        }
    }

    @Test
    void deleteSingleShouldRemoveMachineAndResourceAndBump() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            RecordingMachineService service = service(List.of(machine(201L)));
            db.when(() -> Db.removeById(201L, Resource.class)).thenReturn(true);

            assertTrue(service.deleteMachineByMachineId(201L));

            assertEquals(List.of(201L), service.removedById);
            db.verify(() -> Db.removeById(201L, Resource.class), times(1));
            verify(versionService).bump();
        }
    }

    @Test
    void deleteBatchShouldRejectWholeBatchWhenAnyMachineMissing() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            // 202 无 machine 行（如经历史脏列表拿到的资源 id）：整批拒绝，一行都不删
            RecordingMachineService service = service(List.of(machine(201L)));

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.deleteMachineByMachineIds(new String[] {"201", "202"}));
            assertEquals("机台不存在: [202]", ex.getMessage());
            assertTrue(service.removedByIds.isEmpty());
            db.verify(() -> Db.removeByIds(anyList(), eq(Resource.class)), never());
            verify(versionService, never()).bump();
        }
    }

    @Test
    void deleteBatchShouldRemoveAllAndBumpWhenAllExist() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            RecordingMachineService service = service(List.of(machine(201L), machine(202L)));
            db.when(() -> Db.removeByIds(anyList(), eq(Resource.class))).thenReturn(true);

            assertTrue(service.deleteMachineByMachineIds(new String[] {"201", "202"}));

            assertEquals(1, service.removedByIds.size());
            assertEquals(List.of(201L, 202L), service.removedByIds.get(0));
            verify(versionService).bump();
        }
    }

    @Test
    void deleteBatchShouldReturnFalseForNullOrEmptyWithoutSideEffects() {
        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            RecordingMachineService service = service(List.of(machine(201L)));

            assertFalse(service.deleteMachineByMachineIds(null));
            assertFalse(service.deleteMachineByMachineIds(new String[0]));
            assertTrue(service.listedRequests.isEmpty());
            verify(versionService, never()).bump();
        }
    }

    private Machine machine(long machineId) {
        Machine machine = new Machine();
        machine.setMachineId(machineId);
        machine.setTonnage(200);
        return machine;
    }

    private RecordingMachineService service(List<Machine> machines) {
        RecordingMachineService service = new RecordingMachineService(machines);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "versionService", versionService);
        return service;
    }

    /**
     * 记录桩（对齐 RecordingTaskEventService 模式：覆写 listByIds/removeById/removeByIds 边界，
     * resource 表删除经 mockStatic(Db.class) 拦截）。
     */
    private static final class RecordingMachineService extends MachineServiceImpl {

        private final List<Machine> machines;
        final List<Serializable> removedById = new ArrayList<>();
        final List<Collection<?>> removedByIds = new ArrayList<>();
        final List<Collection<? extends Serializable>> listedRequests = new ArrayList<>();

        private RecordingMachineService(List<Machine> machines) {
            this.machines = machines;
        }

        @Override
        public List<Machine> listByIds(Collection<? extends Serializable> idList) {
            listedRequests.add(idList);
            Set<Serializable> wanted = new HashSet<>(idList);
            List<Machine> found = new ArrayList<>();
            for (Machine machine : machines) {
                if (wanted.contains(machine.getMachineId())) {
                    found.add(machine);
                }
            }
            return found;
        }

        @Override
        public boolean removeById(Serializable id) {
            removedById.add(id);
            return true;
        }

        @Override
        public boolean removeByIds(Collection<?> list) {
            removedByIds.add(list);
            return true;
        }
    }
}
