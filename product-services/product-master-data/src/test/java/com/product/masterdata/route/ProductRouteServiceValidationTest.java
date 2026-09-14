package com.product.masterdata.route;

import com.product.masterdata.common.constant.RouteOperationConstants;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.domain.entity.RouteOperation;
import com.product.masterdata.route.RouteOperationValidator;
import com.product.masterdata.route.RouteRuleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ProductRouteService 入参校验测试（不依赖数据库；单体 product-pps
 * ProductRouteServiceValidationTest 的服务化迁移——路线写路径校验归 master-data，
 * registry 为服务内简化码表版，校验语义与单体一致）。
 */
class ProductRouteServiceValidationTest {

    private RouteOperationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RouteOperationValidator(new RouteRuleRegistry());
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
