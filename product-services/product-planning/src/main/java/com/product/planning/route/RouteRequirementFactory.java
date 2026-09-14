package com.product.planning.route;

import com.product.planning.domain.entity.TaskResourceRequirement;

/**
 * 任务资源需求构建辅助。
 */
public final class RouteRequirementFactory {

    private RouteRequirementFactory() {
    }

    public static TaskResourceRequirement createRequirement(Long taskId,
                                                     String resourceType,
                                                     String resourceRole,
                                                     String capabilityCode) {
        TaskResourceRequirement requirement = new TaskResourceRequirement();
        requirement.setTaskId(taskId);
        requirement.setResourceType(resourceType);
        requirement.setResourceRole(resourceRole);
        requirement.setCapabilityCode(capabilityCode);
        requirement.setRequiredCount(1);
        requirement.setIsMandatory(1);
        return requirement;
    }
}
