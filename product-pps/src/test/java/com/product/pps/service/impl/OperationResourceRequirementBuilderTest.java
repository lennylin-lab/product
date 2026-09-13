package com.product.pps.service.impl;

import com.product.common.constant.ResourceConstants;
import com.product.common.constant.RouteOperationConstants;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.RouteOperation;
import com.product.domain.entity.TaskResourceRequirement;
import com.product.pps.route.RouteRuleRegistry;
import com.product.pps.route.model.InjectA2TimeModel;
import com.product.pps.route.model.PostUnitTimeModel;
import com.product.pps.route.model.SetupBaseTimeModel;
import com.product.pps.route.rule.InjectMachineRule;
import com.product.pps.route.rule.PostWorkstationRule;
import com.product.pps.route.rule.SetupMachineRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationResourceRequirementBuilderTest {

    private OperationResourceRequirementBuilder builder;

    @BeforeEach
    void setUp() {
        RouteRuleRegistry registry = new RouteRuleRegistry(
                List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule()),
                List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                        new InjectA2TimeModel(new InjectDurationCalculator())));
        builder = new OperationResourceRequirementBuilder(registry);
    }

    @Test
    void buildRequirementsShouldUsePostWorkstationRule() {
        OperationTask task = new OperationTask();
        task.setTaskId(514L);
        task.setOpCode("POST_QC_PUTAWAY");

        RouteOperation routeOperation = new RouteOperation();
        routeOperation.setEligibleResourceRule(RouteOperationConstants.RULE_POST_WORKSTATION);
        routeOperation.setOpCode("POST_QC_PUTAWAY");

        List<TaskResourceRequirement> requirements = builder.buildRequirements(task, routeOperation);

        assertEquals(2, requirements.size());
        assertTrue(requirements.stream().anyMatch(req ->
                ResourceConstants.RESOURCE_TYPE_PERSON.equals(req.getResourceType())));
        assertTrue(requirements.stream().anyMatch(req ->
                ResourceConstants.RESOURCE_TYPE_WORKSTATION.equals(req.getResourceType())));
        assertTrue(requirements.stream().noneMatch(req ->
                ResourceConstants.RESOURCE_TYPE_MACHINE.equals(req.getResourceType())));
    }
}
