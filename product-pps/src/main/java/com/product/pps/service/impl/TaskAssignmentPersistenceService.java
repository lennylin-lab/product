package com.product.pps.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.common.constant.ResourceConstants;
import com.product.common.constant.StatusConstants;
import com.product.common.exception.ServiceException;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.entity.TaskAssignmentResource;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.mapper.OperationTaskMapper;
import com.product.pps.mapper.TaskAssignmentMapper;
import com.product.pps.mapper.TaskAssignmentResourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 派工结果持久化服务。
 *
 * 说明：负责排程结果的防重检查、批量写入和任务状态回写。
 * 排程算法本身留在 {@link TaskSchedulingCalculator}，编排流程留在 {@link TaskAssignmentServiceImpl}。
 */
@Slf4j
@Service
public class TaskAssignmentPersistenceService extends ServiceImpl<TaskAssignmentMapper, TaskAssignment> {
    @Autowired
    private OperationTaskMapper operationTaskMapper;
    @Autowired
    private TaskAssignmentResourceMapper taskAssignmentResourceMapper;

    /**
     * 持久化单个批次的排程结果。
     */
    public boolean persistBatchResult(TaskSchedulingCalculator.ScheduleBatchResult batchResult, int batchSize) {
        // 查询哪些 task_id 已经存在派工记录
        if (batchResult == null || CollectionUtils.isEmpty(batchResult.getAssignments())) {
            return true;
        }

        List<String> existingTaskIds = baseMapper.selectExistingTaskIds(batchResult.getTaskIds());
        if (CollectionUtils.isNotEmpty(existingTaskIds)) {
            throw new ServiceException("任务已存在派工记录: " + String.join(",", existingTaskIds));
        }
        // 这里需要
        boolean saved = saveBatch(batchResult.getAssignments(), batchSize);
        if (!saved) {
            return false;
        }
        persistAssignedResources(batchResult.getAssignments(), batchSize);

        int updated = operationTaskMapper.batchMarkScheduled(
                batchResult.getTaskIds(),
                StatusConstants.READY_OPERATION_TASK,
                StatusConstants.SCHEDULED_OPERATION_TASK
        );
        if (updated != batchResult.getTaskIds().size()) {
            throw new ServiceException("任务状态更新失败，预期更新" + batchResult.getTaskIds().size() + "条，实际更新" + updated + "条");
        }
        return true;
    }

    void persistAssignedResources(List<TaskAssignment> assignments, int batchSize) {
        List<TaskAssignmentResource> assignedResources = buildAssignedResources(assignments);
        if (CollectionUtils.isEmpty(assignedResources)) {
            return;
        }
        int inserted = taskAssignmentResourceMapper.batchInsert(assignedResources);
        if (inserted != assignedResources.size()) {
            throw new ServiceException("派工资源明细写入失败，预期写入" + assignedResources.size() + "条，实际写入" + inserted + "条");
        }
    }

    List<TaskAssignmentResource> buildAssignedResources(List<TaskAssignment> assignments) {
        if (CollectionUtils.isEmpty(assignments)) {
            return List.of();
        }
        List<TaskAssignmentResource> rows = new ArrayList<>();
        for (TaskAssignment assignment : assignments) {
            if (assignment == null) {
                continue;
            }
            Long assignmentId = assignment.getAssignmentId();
            if (assignmentId == null) {
                throw new ServiceException("派工资源明细写入失败，assignmentId 为空: " + assignment.getTaskId());
            }
            Set<String> dedupeKeys = new HashSet<>();
            appendMachineAssignment(rows, dedupeKeys, assignment, assignmentId);
            appendRequirementAssignments(rows, dedupeKeys, assignment, assignmentId);
        }
        return rows;
    }

    private void appendMachineAssignment(List<TaskAssignmentResource> rows,
                                         Set<String> dedupeKeys,
                                         TaskAssignment assignment,
                                         Long assignmentId) {
        if (assignment == null || assignmentId == null || org.apache.commons.lang3.StringUtils.isBlank(assignment.getMachineId())) {
            return;
        }
        String dedupeKey = buildDedupeKey(ResourceConstants.RESOURCE_TYPE_MACHINE, assignment.getMachineId());
        if (!dedupeKeys.add(dedupeKey)) {
            return;
        }
        TaskAssignmentResource row = new TaskAssignmentResource();
        row.setAssignmentId(assignmentId);
        row.setTaskId(assignment.getTaskId());
        row.setResourceId(assignment.getMachineId());
        row.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        row.setPlannedStart(assignment.getPlannedStart());
        row.setPlannedEnd(assignment.getPlannedEnd());
        row.setSequenceOnResource(assignment.getSequenceOnResource());
        rows.add(row);
    }

    private void appendRequirementAssignments(List<TaskAssignmentResource> rows,
                                              Set<String> dedupeKeys,
                                              TaskAssignment assignment,
                                              Long assignmentId) {
        if (assignment == null || CollectionUtils.isEmpty(assignment.getResourceRequirementList())) {
            return;
        }
        Map<String, Long> resourceSequenceMap = assignment.getResourceSequenceMap();
        for (TaskResourceRequirement requirement : assignment.getResourceRequirementList()) {
            if (requirement == null || org.apache.commons.lang3.StringUtils.isBlank(requirement.getResourceId())
                    || org.apache.commons.lang3.StringUtils.isBlank(requirement.getResourceType())) {
                continue;
            }
            String dedupeKey = buildDedupeKey(requirement.getResourceType(), requirement.getResourceId());
            if (!dedupeKeys.add(dedupeKey)) {
                continue;
            }
            TaskAssignmentResource row = new TaskAssignmentResource();
            row.setAssignmentId(assignmentId);
            row.setTaskId(assignment.getTaskId());
            row.setResourceId(requirement.getResourceId());
            row.setResourceType(requirement.getResourceType());
            row.setResourceRole(requirement.getResourceRole());
            row.setRequirementId(requirement.getRequirementId());
            row.setPlannedStart(assignment.getPlannedStart());
            row.setPlannedEnd(assignment.getPlannedEnd());
            // 使用各资源类型的独立序号（而非统一使用机台序号）
            Long sequence = resourceSequenceMap != null
                    ? resourceSequenceMap.get(requirement.getResourceType())
                    : null;
            row.setSequenceOnResource(sequence != null ? sequence : assignment.getSequenceOnResource());
            rows.add(row);
        }
    }

    private String buildDedupeKey(String resourceType, String resourceId) {
        return String.join("::",
                Objects.toString(resourceType, ""),
                Objects.toString(resourceId, ""));
    }

    /**
     * 持久化所有批次的排程结果。
     */
    public boolean persistAllBatchResults(List<TaskSchedulingCalculator.ScheduleBatchResult> batchResults, int batchSize) {
        if (CollectionUtils.isEmpty(batchResults)) {
            return true;
        }
        for (TaskSchedulingCalculator.ScheduleBatchResult batchResult : batchResults) {
            if (!persistBatchResult(batchResult, batchSize)) {
                return false;
            }
        }
        return true;
    }
}
