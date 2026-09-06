package com.product.pps.route.rule;

import com.product.common.constant.ResourceConstants;
import com.product.common.constant.RouteOperationConstants;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.route.RouteRequirementFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PostWorkstationRule implements RouteEligibleResourceRule {

    @Override
    public String code() {
        return RouteOperationConstants.RULE_POST_WORKSTATION;
    }

    @Override
    public List<TaskResourceRequirement> buildRequirements(OperationTask task, String opCode) {
        List<TaskResourceRequirement> requirements = new ArrayList<>(2);
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_PERSON, "质检员", opCode));
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_WORKSTATION, null, opCode));
        return requirements;
    }

    @Override
    public boolean triggersChangeover() {
        return false;
    }

    @Override
    public boolean requiresMachine() {
        return false;
    }

    @Override
    public boolean requiresWorkstation() {
        return true;
    }
}
