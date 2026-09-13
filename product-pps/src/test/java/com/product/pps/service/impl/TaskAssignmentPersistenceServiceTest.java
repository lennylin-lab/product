package com.product.pps.service.impl;

import com.product.common.constant.ResourceConstants;
import com.product.common.constant.StatusConstants;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskAssignmentResource;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.mapper.OperationTaskMapper;
import com.product.pps.mapper.TaskAssignmentMapper;
import com.product.pps.mapper.TaskAssignmentResourceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskAssignmentPersistenceServiceTest {

    @Test
    void persistBatchResultShouldWriteMainAssignmentAndAssignedResources() {
        TaskAssignmentMapper taskAssignmentMapper = mock(TaskAssignmentMapper.class);
        TaskAssignmentResourceMapper taskAssignmentResourceMapper = mock(TaskAssignmentResourceMapper.class);
        OperationTaskMapper operationTaskMapper = mock(OperationTaskMapper.class);

        TestableTaskAssignmentPersistenceService service = new TestableTaskAssignmentPersistenceService();
        ReflectionTestUtils.setField(service, "baseMapper", taskAssignmentMapper);
        ReflectionTestUtils.setField(service, "taskAssignmentResourceMapper", taskAssignmentResourceMapper);
        ReflectionTestUtils.setField(service, "operationTaskMapper", operationTaskMapper);

        TaskAssignment assignment = buildAssignment();
        TaskSchedulingCalculator.ScheduleBatchResult batchResult =
                new TaskSchedulingCalculator.ScheduleBatchResult(List.of(assignment), List.of(510L));

        when(taskAssignmentMapper.selectExistingTaskIds(anyList())).thenReturn(List.of());
        when(taskAssignmentResourceMapper.batchInsert(anyList())).thenAnswer(invocation -> {
            List<?> rows = invocation.getArgument(0);
            return rows.size();
        });
        when(operationTaskMapper.batchMarkScheduled(eq(List.of(510L)),
                eq(StatusConstants.READY_OPERATION_TASK),
                eq(StatusConstants.SCHEDULED_OPERATION_TASK))).thenReturn(1);

        assertTrue(service.persistBatchResult(batchResult, 10));

        ArgumentCaptor<List<TaskAssignmentResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskAssignmentResourceMapper).batchInsert(captor.capture());
        List<TaskAssignmentResource> rows = captor.getValue();
        assertEquals(3, rows.size());
        assertEquals(ResourceConstants.RESOURCE_TYPE_MACHINE, rows.get(0).getResourceType());
        assertEquals(ResourceConstants.RESOURCE_TYPE_PERSON, rows.get(1).getResourceType());
        assertEquals(ResourceConstants.RESOURCE_TYPE_WORKSTATION, rows.get(2).getResourceType());
    }

    private TaskAssignment buildAssignment() {
        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(510L);
        assignment.setMachineId(103L);
        assignment.setPlannedStart(LocalDateTime.of(2026, 4, 28, 8, 0));
        assignment.setPlannedEnd(LocalDateTime.of(2026, 4, 28, 10, 0));
        assignment.setSequenceOnResource(3L);

        TaskResourceRequirement machineReq = new TaskResourceRequirement();
        machineReq.setRequirementId(701L);
        machineReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        machineReq.setResourceId(103L);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setRequirementId(705L);
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setResourceId(301L);
        personReq.setResourceRole("OPERATOR");

        TaskResourceRequirement workstationReq = new TaskResourceRequirement();
        workstationReq.setRequirementId(702L);
        workstationReq.setResourceType(ResourceConstants.RESOURCE_TYPE_WORKSTATION);
        workstationReq.setResourceId(401L);
        workstationReq.setResourceRole("LINE");

        assignment.setResourceRequirementList(List.of(machineReq, personReq, workstationReq));
        return assignment;
    }

    @Test
    void buildAssignedResourcesShouldUseResourceSpecificSequence() {
        TestableTaskAssignmentPersistenceService service = new TestableTaskAssignmentPersistenceService();

        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(520L);
        assignment.setMachineId(103L);
        assignment.setPlannedStart(LocalDateTime.of(2026, 4, 28, 8, 0));
        assignment.setPlannedEnd(LocalDateTime.of(2026, 4, 28, 10, 0));
        assignment.setSequenceOnResource(3L);

        Map<String, Long> resourceSequenceMap = new HashMap<>();
        resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_MACHINE, 3L);
        resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_MOLD, 7L);
        resourceSequenceMap.put(ResourceConstants.RESOURCE_TYPE_PERSON, 5L);
        assignment.setResourceSequenceMap(resourceSequenceMap);

        TaskResourceRequirement moldReq = new TaskResourceRequirement();
        moldReq.setRequirementId(703L);
        moldReq.setResourceType(ResourceConstants.RESOURCE_TYPE_MOLD);
        moldReq.setResourceId(201L);

        TaskResourceRequirement personReq = new TaskResourceRequirement();
        personReq.setRequirementId(704L);
        personReq.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        personReq.setResourceId(301L);

        assignment.setResourceRequirementList(List.of(moldReq, personReq));

        // Simulate assignmentId generation
        assignment.setAssignmentId(1L);

        List<TaskAssignmentResource> resources = service.buildAssignedResources(List.of(assignment));

        assertEquals(3, resources.size());

        // MACHINE entry (from appendMachineAssignment) uses machine sequence
        TaskAssignmentResource machineResource = resources.stream()
                .filter(r -> ResourceConstants.RESOURCE_TYPE_MACHINE.equals(r.getResourceType()))
                .findFirst().orElseThrow();
        assertEquals(3L, machineResource.getSequenceOnResource());

        // MOLD entry uses mold-specific sequence
        TaskAssignmentResource moldResource = resources.stream()
                .filter(r -> ResourceConstants.RESOURCE_TYPE_MOLD.equals(r.getResourceType()))
                .findFirst().orElseThrow();
        assertEquals(7L, moldResource.getSequenceOnResource());

        // PERSON entry uses person-specific sequence
        TaskAssignmentResource personResource = resources.stream()
                .filter(r -> ResourceConstants.RESOURCE_TYPE_PERSON.equals(r.getResourceType()))
                .findFirst().orElseThrow();
        assertEquals(5L, personResource.getSequenceOnResource());
    }

    private static class TestableTaskAssignmentPersistenceService extends TaskAssignmentPersistenceService {
        @Override
        public boolean saveBatch(java.util.Collection<TaskAssignment> entityList, int batchSize) {
            long id = 1L;
            for (TaskAssignment assignment : entityList) {
                assignment.setAssignmentId(id++);
            }
            return true;
        }
    }
}
