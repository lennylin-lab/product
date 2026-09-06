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
public class SetupMachineRule implements RouteEligibleResourceRule {

    @Override
    public String code() {
        return RouteOperationConstants.RULE_SETUP_MACHINE;
    }

    @Override
    public List<TaskResourceRequirement> buildRequirements(OperationTask task, String opCode) {
        List<TaskResourceRequirement> requirements = new ArrayList<>(2);
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_PERSON, "调机员", opCode));
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_MACHINE, null, opCode));
        return requirements;
    }

    @Override
    public boolean triggersChangeover() {
        return true;
    }

    @Override
    public boolean requiresMachine() {
        return true;
    }

    @Override
    public boolean requiresWorkstation() {
        return false;
    }
}
