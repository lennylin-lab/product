package com.product.planning.service.impl;

import com.product.planning.common.constant.StatusConstants;
import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.domain.model.Calendar;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.model.Resource;
import com.product.planning.domain.model.ResourceCapability;
import com.product.planning.domain.entity.TaskResourceRequirement;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingQueryServiceTest {

    private final TaskSchedulingQueryService queryService = new TaskSchedulingQueryService(null);

    @Test
    void normalizeReadyTasksForSchedulingShouldFilterAndSortTasksDeterministically() {
        OperationTask laterTask = buildTask(519L, 602L, 2L,
                LocalDateTime.of(2026, 4, 21, 10, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask earliestTask = buildTask(524L, 601L, 2L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask sameTimeLowerSequence = buildTask(511L, 601L, 1L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask duplicateTask = buildTask(511L, 609L, 9L,
                LocalDateTime.of(2026, 4, 21, 12, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask nonReadyTask = buildTask(525L, 603L, 1L,
                LocalDateTime.of(2026, 4, 21, 7, 0), StatusConstants.SCHEDULED_OPERATION_TASK);
        OperationTask emptyIdTask = buildTask(null, 604L, 1L,
                LocalDateTime.of(2026, 4, 21, 9, 0), StatusConstants.READY_OPERATION_TASK);

        List<OperationTask> result = queryService.normalizeReadyTasksForScheduling(
                List.of(laterTask, earliestTask, sameTimeLowerSequence, duplicateTask, nonReadyTask, emptyIdTask),
                Set.of(519L));

        assertEquals(List.of(511L, 524L),
                result.stream().map(OperationTask::getTaskId).toList());
    }

    @Test
    void normalizeReadyTasksForSchedulingShouldReturnEmptyWhenInputInvalid() {
        OperationTask assignedTask = buildTask(503L, 601L, 1L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        OperationTask nullStartTask = buildTask(526L, 601L, 2L, null, StatusConstants.READY_OPERATION_TASK);

        List<OperationTask> result = queryService.normalizeReadyTasksForScheduling(
                Arrays.asList(assignedTask, null, nullStartTask),
                Set.of(503L, 526L));

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveRequiredResourceTypesShouldIncludeMachineAndCollaborativeTypes() {
        OperationTask task = buildTask(503L, 601L, 1L,
                LocalDateTime.of(2026, 4, 21, 8, 0), StatusConstants.READY_OPERATION_TASK);
        TaskResourceRequirement person = new TaskResourceRequirement();
        person.setResourceType(ResourceConstants.RESOURCE_TYPE_PERSON);
        TaskResourceRequirement workstation = new TaskResourceRequirement();
        workstation.setResourceType(ResourceConstants.RESOURCE_TYPE_WORKSTATION);
        task.setResourceRequirementList(List.of(person, workstation));

        Set<String> result = queryService.resolveRequiredResourceTypes(List.of(task));

        assertEquals(Set.of(
                ResourceConstants.RESOURCE_TYPE_PERSON,
                ResourceConstants.RESOURCE_TYPE_WORKSTATION), result);
    }

    @Test
    void buildSchedulingResourceContextShouldGroupResourcesAndAttachCapabilities() {
        Resource machine = buildResource(103L, ResourceConstants.RESOURCE_TYPE_MACHINE, 1L);
        Resource person = buildResource(301L, ResourceConstants.RESOURCE_TYPE_PERSON, 2L);
        Resource workstation = buildResource(401L, ResourceConstants.RESOURCE_TYPE_WORKSTATION, 3L);

        ResourceCapability machineCapability = new ResourceCapability();
        machineCapability.setResourceId(103L);
        machineCapability.setOpCode("INJECT");
        ResourceCapability personCapability = new ResourceCapability();
        personCapability.setResourceId(301L);
        personCapability.setOpCode("SETUP");

        queryService.attachResourceCapabilities(List.of(machine, person, workstation),
                List.of(machineCapability, personCapability));

        Calendar machineCalendar = new Calendar();
        machineCalendar.setCalendarId(1L);
        Calendar personCalendar = new Calendar();
        personCalendar.setCalendarId(2L);
        Calendar workstationCalendar = new Calendar();
        workstationCalendar.setCalendarId(3L);

        TaskSchedulingQueryService.SchedulingResourceContext context = queryService.buildSchedulingResourceContext(
                List.of(machine, person, workstation),
                Map.of(1L, machineCalendar, 2L, personCalendar, 3L, workstationCalendar));

        assertEquals(List.of(machine), context.getResourcesByType().get(ResourceConstants.RESOURCE_TYPE_MACHINE));
        assertEquals(List.of(person), context.getResourcesByType().get(ResourceConstants.RESOURCE_TYPE_PERSON));
        assertEquals(List.of(workstation), context.getResourcesByType().get(ResourceConstants.RESOURCE_TYPE_WORKSTATION));
        assertEquals(1, machine.getCapabilityList().size());
        assertEquals("SETUP", person.getCapabilityList().get(0).getOpCode());
        assertTrue(workstation.getCapabilityList().isEmpty());
        assertEquals(3, context.getCalendarMap().size());
    }

    private OperationTask buildTask(Long taskId,
                                    Long batchId,
                                    Long sequence,
                                    LocalDateTime earliestStart,
                                    String status) {
        OperationTask task = new OperationTask();
        task.setTaskId(taskId);
        task.setBatchId(batchId);
        task.setSequence(sequence);
        task.setEarliestStart(earliestStart);
        task.setStatus(status);
        return task;
    }

    private Resource buildResource(Long resourceId, String resourceType, Long calendarId) {
        Resource resource = new Resource();
        resource.setResourceId(resourceId);
        resource.setResourceType(resourceType);
        resource.setCalendarId(calendarId);
        return resource;
    }
}
