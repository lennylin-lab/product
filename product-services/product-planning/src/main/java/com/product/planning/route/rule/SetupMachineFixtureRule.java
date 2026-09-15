package com.product.planning.route.rule;

import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.route.RouteRequirementFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 换模调机（夹具感知）规则：人员 + 机台 + 夹具（2026-09-15 夹具兼容任务新增）。
 *
 * <p>工序启用方式与既有规则一致：{@code route_operation.eligible_resource_rule}
 * 配置为本规则码即产出强制 FIXTURE 需求行（isMandatory=1、resourceId 为 null——
 * 夹具选择发生在排程计算器）；不配置则走既有 SETUP 兜底，旧行为零变化。
 * 兼容性数据经快照 Fixture.moldCompatibilityList（fixture ↔ mold 显式允许清单）供
 * 计算器过滤。</p>
 */
@Component
public class SetupMachineFixtureRule implements RouteEligibleResourceRule {

    @Override
    public String code() {
        return RouteOperationConstants.RULE_SETUP_MACHINE_FIXTURE;
    }

    @Override
    public List<TaskResourceRequirement> buildRequirements(OperationTask task, String opCode) {
        List<TaskResourceRequirement> requirements = new ArrayList<>(3);
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_PERSON, "调机员", opCode));
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_MACHINE, null, opCode));
        requirements.add(RouteRequirementFactory.createRequirement(
                task.getTaskId(), ResourceConstants.RESOURCE_TYPE_FIXTURE, null, opCode));
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

    @Override
    public boolean requiresFixture() {
        return true;
    }
}
