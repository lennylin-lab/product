package com.product.planning.route.rule;

import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.TaskResourceRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧三规则（RULE_SETUP_MACHINE / RULE_INJECT_MACHINE / RULE_POST_WORKSTATION）
 * buildRequirements 输出逐字段回归测试（2026-09-15 夹具兼容任务）。
 *
 * <p>Review Gate：夹具兼容增量不得改变既有三规则的需求产出——本测试对每条需求行的
 * 全部字段（requirementId/taskId/resourceType/resourceRole/resourceId/capabilityCode/
 * requiredCount/isMandatory/changeoverSourceResourceId/changeoverTimeMin）与
 * 规则语义标志（code/triggersChangeover/requiresMachine/requiresWorkstation/
 * requiresFixture 默认值）逐项断言；任何字段变化即 FAIL。</p>
 */
class LegacyRouteRuleRegressionTest {

    @Test
    void setupMachineRuleRequirementsShouldStayFieldIdentical() {
        SetupMachineRule rule = new SetupMachineRule();
        OperationTask task = new OperationTask();
        task.setTaskId(510L);

        List<TaskResourceRequirement> requirements = rule.buildRequirements(task, "SETUP");

        assertEquals(2, requirements.size());
        assertRequirement(requirements.get(0), 510L, "PERSON", "调机员", null, "SETUP");
        assertRequirement(requirements.get(1), 510L, "MACHINE", null, null, "SETUP");

        assertEquals("RULE_SETUP_MACHINE", rule.code());
        assertTrue(rule.triggersChangeover());
        assertTrue(rule.requiresMachine());
        assertTrue(!rule.requiresWorkstation());
    }

    @Test
    void injectMachineRuleRequirementsShouldStayFieldIdentical() {
        InjectMachineRule rule = new InjectMachineRule();
        OperationTask task = new OperationTask();
        task.setTaskId(511L);

        List<TaskResourceRequirement> requirements = rule.buildRequirements(task, "INJECT");

        assertEquals(3, requirements.size());
        assertRequirement(requirements.get(0), 511L, "PERSON", "操作员", null, "INJECT");
        assertRequirement(requirements.get(1), 511L, "MACHINE", null, null, "INJECT");
        assertRequirement(requirements.get(2), 511L, "MOLD", null, null, "INJECT");

        assertEquals("RULE_INJECT_MACHINE", rule.code());
        assertTrue(!rule.triggersChangeover());
        assertTrue(rule.requiresMachine());
        assertTrue(!rule.requiresWorkstation());
    }

    @Test
    void postWorkstationRuleRequirementsShouldStayFieldIdentical() {
        PostWorkstationRule rule = new PostWorkstationRule();
        OperationTask task = new OperationTask();
        task.setTaskId(512L);

        List<TaskResourceRequirement> requirements = rule.buildRequirements(task, "POST_QC_PUTAWAY");

        assertEquals(2, requirements.size());
        assertRequirement(requirements.get(0), 512L, "PERSON", "质检员", null, "POST_QC_PUTAWAY");
        assertRequirement(requirements.get(1), 512L, "WORKSTATION", null, null, "POST_QC_PUTAWAY");

        assertEquals("RULE_POST_WORKSTATION", rule.code());
        assertTrue(!rule.triggersChangeover());
        assertTrue(!rule.requiresMachine());
        assertTrue(rule.requiresWorkstation());
    }

    @Test
    void legacyRulesShouldNotRequireFixtureByDefault() {
        // 接口 default 方法：既有规则不覆写即返回 false（夹具需求仅由显式新规则码产生）
        assertTrue(!new SetupMachineRule().requiresFixture());
        assertTrue(!new InjectMachineRule().requiresFixture());
        assertTrue(!new PostWorkstationRule().requiresFixture());
    }

    /** 需求行全字段断言（RouteRequirementFactory 约定：requiredCount=1、isMandatory=1）。 */
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
