package com.product.planning.route.rule;

import com.product.planning.common.constant.ResourceConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.TaskResourceRequirement;
import com.product.planning.domain.model.RouteOperation;
import com.product.planning.route.RouteRuleRegistry;
import com.product.planning.route.model.InjectA2TimeModel;
import com.product.planning.route.model.PostUnitTimeModel;
import com.product.planning.route.model.SetupBaseTimeModel;
import com.product.planning.service.impl.InjectDurationCalculator;
import com.product.planning.service.impl.OperationResourceRequirementBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 夹具感知规则单测（2026-09-15 夹具兼容任务）：
 * 1) SetupMachineFixtureRule / InjectMachineFixtureRule 的 buildRequirements 断言
 *    （类型、mandatory=1、resourceId=null、resourceRole、capabilityCode）与规则语义标志
 *    （code / triggersChangeover / requiresMachine / requiresFixture）；
 * 2) 新规则码经 RouteRuleRegistry 可解析（findRule/requireRule），旧规则码不受影响；
 * 3) 仅配置新规则码的工序产出 FIXTURE 需求行；未配置新码的工序不产生 FIXTURE 需求行。
 */
class FixtureAwareRouteRuleTest {

    private RouteRuleRegistry registry;

    private OperationResourceRequirementBuilder builder;

    @BeforeEach
    void setUp() {
        registry = new RouteRuleRegistry(
                List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule(),
                        new SetupMachineFixtureRule(), new InjectMachineFixtureRule()),
                List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                        new InjectA2TimeModel(new InjectDurationCalculator())));
        builder = new OperationResourceRequirementBuilder(registry);
    }

    @Test
    void setupMachineFixtureRuleShouldBuildPersonMachineFixtureRequirements() {
        SetupMachineFixtureRule rule = new SetupMachineFixtureRule();
        OperationTask task = new OperationTask();
        task.setTaskId(620L);

        List<TaskResourceRequirement> requirements = rule.buildRequirements(task, "SETUP");

        assertEquals(3, requirements.size());
        assertRequirement(requirements.get(0), 620L, ResourceConstants.RESOURCE_TYPE_PERSON,
                "调机员", null, "SETUP");
        assertRequirement(requirements.get(1), 620L, ResourceConstants.RESOURCE_TYPE_MACHINE,
                null, null, "SETUP");
        assertRequirement(requirements.get(2), 620L, ResourceConstants.RESOURCE_TYPE_FIXTURE,
                null, null, "SETUP");

        assertEquals(RouteOperationConstants.RULE_SETUP_MACHINE_FIXTURE, rule.code());
        assertTrue(rule.triggersChangeover());
        assertTrue(rule.requiresMachine());
        assertTrue(!rule.requiresWorkstation());
        assertTrue(rule.requiresFixture());
    }

    @Test
    void injectMachineFixtureRuleShouldBuildMachineFixtureRequirements() {
        InjectMachineFixtureRule rule = new InjectMachineFixtureRule();
        OperationTask task = new OperationTask();
        task.setTaskId(621L);

        List<TaskResourceRequirement> requirements = rule.buildRequirements(task, "INJECT");

        assertEquals(2, requirements.size());
        assertRequirement(requirements.get(0), 621L, ResourceConstants.RESOURCE_TYPE_MACHINE,
                null, null, "INJECT");
        assertRequirement(requirements.get(1), 621L, ResourceConstants.RESOURCE_TYPE_FIXTURE,
                null, null, "INJECT");

        assertEquals(RouteOperationConstants.RULE_INJECT_MACHINE_FIXTURE, rule.code());
        assertTrue(rule.triggersChangeover());
        assertTrue(rule.requiresMachine());
        assertTrue(!rule.requiresWorkstation());
        assertTrue(rule.requiresFixture());
    }

    @Test
    void registryShouldResolveFixtureAwareRuleCodes() {
        assertTrue(registry.findRule(RouteOperationConstants.RULE_SETUP_MACHINE_FIXTURE).isPresent());
        assertTrue(registry.findRule(RouteOperationConstants.RULE_INJECT_MACHINE_FIXTURE).isPresent());
        assertEquals(RouteOperationConstants.RULE_SETUP_MACHINE_FIXTURE,
                registry.requireRule(RouteOperationConstants.RULE_SETUP_MACHINE_FIXTURE).code());
        assertEquals(RouteOperationConstants.RULE_INJECT_MACHINE_FIXTURE,
                registry.requireRule(RouteOperationConstants.RULE_INJECT_MACHINE_FIXTURE).code());
        // 旧规则码解析不受新规则注册影响
        assertTrue(registry.findRule(RouteOperationConstants.RULE_SETUP_MACHINE).isPresent());
        assertTrue(registry.findRule(RouteOperationConstants.RULE_INJECT_MACHINE).isPresent());
        assertTrue(registry.findRule(RouteOperationConstants.RULE_POST_WORKSTATION).isPresent());
    }

    @Test
    void builderShouldEmitFixtureRequirementOnlyForConfiguredFixtureAwareRule() {
        // 配置新规则码 → 需求含 FIXTURE 强制行
        OperationTask task = new OperationTask();
        task.setTaskId(622L);
        RouteOperation fixtureAware = new RouteOperation();
        fixtureAware.setEligibleResourceRule(RouteOperationConstants.RULE_SETUP_MACHINE_FIXTURE);
        fixtureAware.setOpCode("SETUP");
        List<TaskResourceRequirement> withFixture = builder.buildRequirements(task, fixtureAware);
        assertEquals(3, withFixture.size());
        assertTrue(withFixture.stream()
                .anyMatch(req -> ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(req.getResourceType())));

        // 沿用旧规则码 → 无 FIXTURE 需求行（既有行为零变化）
        RouteOperation legacy = new RouteOperation();
        legacy.setEligibleResourceRule(RouteOperationConstants.RULE_SETUP_MACHINE);
        legacy.setOpCode("SETUP");
        List<TaskResourceRequirement> withoutFixture = builder.buildRequirements(task, legacy);
        assertTrue(withoutFixture.stream()
                .noneMatch(req -> ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(req.getResourceType())));

        // 未配置规则码（opCode 兜底）→ 无 FIXTURE 需求行
        RouteOperation fallback = new RouteOperation();
        fallback.setOpCode("INJECT");
        List<TaskResourceRequirement> fallbackRequirements = builder.buildRequirements(task, fallback);
        assertTrue(fallbackRequirements.stream()
                .noneMatch(req -> ResourceConstants.RESOURCE_TYPE_FIXTURE.equals(req.getResourceType())));
    }

    /**
     * 需求行全字段断言（工厂约定：requiredCount=1、isMandatory=1、resourceId=null，
     * 换型字段与需求ID为空）。
     */
    private void assertRequirement(TaskResourceRequirement requirement, Long taskId, String resourceType,
                                   String resourceRole, Long resourceId, String capabilityCode) {
        assertNotNull(requirement);
        assertNull(requirement.getRequirementId());
        assertEquals(taskId, requirement.getTaskId());
        assertEquals(resourceType, requirement.getResourceType());
        assertEquals(resourceRole, requirement.getResourceRole());
        assertEquals(resourceId, requirement.getResourceId());
        assertEquals(capabilityCode, requirement.getCapabilityCode());
        assertEquals(1, requirement.getRequiredCount());
        assertEquals(1, requirement.getIsMandatory());
        assertNull(requirement.getChangeoverSourceResourceId());
        assertNull(requirement.getChangeoverTimeMin());
    }
}
