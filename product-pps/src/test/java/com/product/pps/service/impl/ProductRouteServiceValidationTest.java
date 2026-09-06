package com.product.pps.service.impl;

import com.product.common.constant.RouteOperationConstants;
import com.product.common.exception.ServiceException;
import com.product.domain.entity.RouteOperation;
import com.product.pps.route.RouteOperationValidator;
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

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ProductRouteService 入参校验测试（不依赖数据库）。
 */
class ProductRouteServiceValidationTest {

    private RouteOperationValidator validator;

    @BeforeEach
    void setUp() {
        RouteRuleRegistry registry = new RouteRuleRegistry(
                List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule()),
                List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                        new InjectA2TimeModel(new InjectDurationCalculator())));
        validator = new RouteOperationValidator(registry);
    }

    @Test
    void createRoutePayloadShouldPassValidation() {
        validator.validate(List.of(
                operation("SETUP", 1),
                operation("INJECT", 2),
                operation("POST_QC_PUTAWAY", 3)));
    }

    @Test
    void createRoutePayloadShouldRejectMissingQueuePolicy() {
        RouteOperation operation = operation("SETUP", 1);
        operation.setQueuePolicy(null);
        assertThrows(ServiceException.class, () -> validator.validate(List.of(operation)));
    }

    private RouteOperation operation(String opCode, int sequence) {
        RouteOperation operation = new RouteOperation();
        operation.setOpCode(opCode);
        operation.setSequence(sequence);
        operation.setEligibleResourceRule(switch (opCode) {
            case "SETUP" -> RouteOperationConstants.RULE_SETUP_MACHINE;
            case "INJECT" -> RouteOperationConstants.RULE_INJECT_MACHINE;
            default -> RouteOperationConstants.RULE_POST_WORKSTATION;
        });
        operation.setStdTimeModel(switch (opCode) {
            case "SETUP" -> RouteOperationConstants.TM_SETUP_BASE;
            case "INJECT" -> RouteOperationConstants.TM_INJECT_A2;
            default -> RouteOperationConstants.TM_POST_UNIT;
        });
        operation.setQueuePolicy(RouteOperationConstants.QUEUE_FIFO);
        return operation;
    }
}
