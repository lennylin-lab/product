package com.product.planning.service.impl;

import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.common.utils.StringUtils;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.model.RouteOperation;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.route.RouteRuleRegistry;
import com.product.planning.route.rule.RouteEligibleResourceRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据路线工序定义构建任务资源需求。
 */
@Component
public class OperationResourceRequirementBuilder {

    private final RouteRuleRegistry routeRuleRegistry;

    @Autowired
    public OperationResourceRequirementBuilder(RouteRuleRegistry routeRuleRegistry) {
        this.routeRuleRegistry = routeRuleRegistry;
    }

    public List<TaskResourceRequirement> buildRequirements(OperationTask task, RouteOperation routeOperation) {
        String ruleCode = routeOperation == null ? null : routeOperation.getEligibleResourceRule();
        String opCode = resolveOpCode(task, routeOperation);
        if (StringUtils.isEmpty(ruleCode)) {
            return buildRequirementsByOpCode(task, opCode);
        }
        return routeRuleRegistry.findRule(ruleCode)
                .map(rule -> rule.buildRequirements(task, opCode))
                .orElseGet(() -> buildRequirementsByOpCode(task, opCode));
    }

    private List<TaskResourceRequirement> buildRequirementsByOpCode(OperationTask task, String opCode) {
        String effectiveOpCode = StringUtils.isEmpty(opCode) ? task.getOpCode() : opCode;
        return switch (effectiveOpCode) {
            case "SETUP" -> requireRule(RouteOperationConstants.RULE_SETUP_MACHINE)
                    .buildRequirements(task, effectiveOpCode);
            case "INJECT" -> requireRule(RouteOperationConstants.RULE_INJECT_MACHINE)
                    .buildRequirements(task, effectiveOpCode);
            case "POST_QC_PUTAWAY" -> requireRule(RouteOperationConstants.RULE_POST_WORKSTATION)
                    .buildRequirements(task, effectiveOpCode);
            default -> List.of();
        };
    }

    private RouteEligibleResourceRule requireRule(String ruleCode) {
        return routeRuleRegistry.requireRule(ruleCode);
    }

    private String resolveOpCode(OperationTask task, RouteOperation routeOperation) {
        if (routeOperation != null && StringUtils.isNotEmpty(routeOperation.getOpCode())) {
            return routeOperation.getOpCode();
        }
        return task.getOpCode();
    }
}
