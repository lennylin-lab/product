package com.product.planning.route;

import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.domain.model.ProductMoldParam;
import com.product.planning.route.model.InjectA2TimeModel;
import com.product.planning.route.model.PostUnitTimeModel;
import com.product.planning.route.model.SetupBaseTimeModel;
import com.product.planning.route.rule.InjectMachineRule;
import com.product.planning.route.rule.PostWorkstationRule;
import com.product.planning.route.rule.SetupMachineRule;
import com.product.planning.service.impl.InjectDurationCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRuleRegistryTest {

    private RouteRuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RouteRuleRegistry(
                List.of(new SetupMachineRule(), new InjectMachineRule(), new PostWorkstationRule()),
                List.of(new SetupBaseTimeModel(), new PostUnitTimeModel(),
                        new InjectA2TimeModel(new InjectDurationCalculator())));
    }

    @Test
    void requireRuleShouldRejectUnknownCode() {
        assertThrows(com.product.planning.common.exception.ServiceException.class,
                () -> registry.requireRule("RULE_UNKNOWN"));
    }

    @Test
    void requireModelShouldAcceptAlias() {
        assertTrue(registry.findModel("TM_INJECT").isPresent());
        assertTrue(registry.findModel("TM_SETUP").isPresent());
        assertTrue(registry.findModel("TM_POST").isPresent());
    }

    @Test
    void calculateDurationMinShouldUseSetupBaseModel() {
        RouteDurationContext context = new RouteDurationContext(
                2L, 1L, null, "SETUP", RouteOperationConstants.TM_SETUP_BASE);
        assertEquals(120L, registry.calculateDurationMin(context));
    }

    @Test
    void calculateDurationMinShouldUseInjectA2Model() {
        ProductMoldParam param = new ProductMoldParam();
        param.setProductId(1L);
        param.setMoldId(101L);
        param.setCycleTimeSec(new BigDecimal("30"));
        param.setCavity(2);
        param.setYieldRate(BigDecimal.ONE);
        param.setUtilization(BigDecimal.ONE);
        RouteDurationContext context = new RouteDurationContext(
                100L, 1L, param, "INJECT", RouteOperationConstants.TM_INJECT_A2);
        assertTrue(registry.calculateDurationMin(context) > 0);
    }

    @Test
    void setupRuleShouldTriggerChangeover() {
        assertTrue(registry.requireRule(RouteOperationConstants.RULE_SETUP_MACHINE).triggersChangeover());
        assertTrue(!registry.requireRule(RouteOperationConstants.RULE_INJECT_MACHINE).triggersChangeover());
    }
}
