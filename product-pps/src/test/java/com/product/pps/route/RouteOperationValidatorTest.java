package com.product.pps.route;

import com.product.common.constant.RouteOperationConstants;
import com.product.common.exception.ServiceException;
import com.product.domain.entity.RouteOperation;
import com.product.pps.route.model.InjectA2TimeModel;
import com.product.pps.route.model.PostUnitTimeModel;
import com.product.pps.route.model.SetupBaseTimeModel;
import com.product.pps.route.rule.InjectMachineRule;
import com.product.pps.route.rule.PostWorkstationRule;
import com.product.pps.route.rule.SetupMachineRule;
import com.product.pps.service.impl.InjectDurationCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteOperationValidatorTest {

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
    void validateShouldAcceptStandardRoute() {
        assertDoesNotThrow(() -> validator.validate(List.of(
                operation("SETUP", 1, RouteOperationConstants.RULE_SETUP_MACHINE,
                        RouteOperationConstants.TM_SETUP_BASE, RouteOperationConstants.QUEUE_FIFO),
                operation("INJECT", 2, RouteOperationConstants.RULE_INJECT_MACHINE,
                        RouteOperationConstants.TM_INJECT_A2, RouteOperationConstants.QUEUE_FIFO),
                operation("POST_QC_PUTAWAY", 3, RouteOperationConstants.RULE_POST_WORKSTATION,
                        RouteOperationConstants.TM_POST_UNIT, RouteOperationConstants.QUEUE_FIFO))));
    }

    @Test
    void validateShouldRejectUnknownRule() {
        RouteOperation operation = operation("PACK", 1, "RULE_UNKNOWN",
                RouteOperationConstants.TM_POST_UNIT, RouteOperationConstants.QUEUE_FIFO);
        assertThrows(ServiceException.class, () -> validator.validate(List.of(operation)));
    }

    @Test
    void validateShouldRejectDuplicateSequence() {
        List<RouteOperation> operations = List.of(
                operation("SETUP", 1, RouteOperationConstants.RULE_SETUP_MACHINE,
                        RouteOperationConstants.TM_SETUP_BASE, RouteOperationConstants.QUEUE_FIFO),
                operation("INJECT", 1, RouteOperationConstants.RULE_INJECT_MACHINE,
                        RouteOperationConstants.TM_INJECT_A2, RouteOperationConstants.QUEUE_FIFO));
        assertThrows(ServiceException.class, () -> validator.validate(operations));
    }

    @Test
    void validateShouldRejectInvalidQueuePolicy() {
        RouteOperation operation = operation("SETUP", 1, RouteOperationConstants.RULE_SETUP_MACHINE,
                RouteOperationConstants.TM_SETUP_BASE, "INVALID");
        assertThrows(ServiceException.class, () -> validator.validate(List.of(operation)));
    }

    private RouteOperation operation(String opCode,
                                     int sequence,
                                     String rule,
                                     String model,
                                     String queuePolicy) {
        RouteOperation operation = new RouteOperation();
        operation.setOpCode(opCode);
        operation.setSequence(sequence);
        operation.setEligibleResourceRule(rule);
        operation.setStdTimeModel(model);
        operation.setQueuePolicy(queuePolicy);
        return operation;
    }
}
