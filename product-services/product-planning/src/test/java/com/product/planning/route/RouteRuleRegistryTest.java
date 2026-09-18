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
        // issue #12：SETUP 为每次换型基准时长，不随批量放大
        assertEquals(60L, registry.calculateDurationMin(context("SETUP", RouteOperationConstants.TM_SETUP_BASE, 1L)));
        assertEquals(60L, registry.calculateDurationMin(context("SETUP", RouteOperationConstants.TM_SETUP_BASE, 100L)));
    }

    @Test
    void calculateDurationMinShouldUsePostBatchBaseModel() {
        // issue #12：POST_QC_PUTAWAY 按批次活动计，不随批量放大
        assertEquals(120L, registry.calculateDurationMin(
                context("POST_QC_PUTAWAY", RouteOperationConstants.TM_POST_UNIT, 100L)));
    }

    @Test
    void calculateDurationMinFallbackShouldNotScaleWithBatchQty() {
        // 未知模型码走兜底基准，同样不乘 batchQty（旧行为 60×100=6000 必然超班次窗口）
        assertEquals(60L, registry.calculateDurationMin(context("SETUP", "TM_UNKNOWN", 100L)));
    }

    @Test
    void realisticBatchQtyShouldStayWithinShiftWindow() {
        // issue #12 回归：qty=100（负载测试规模）三工序时长须全部落在 720min 班次窗口内
        ProductMoldParam param = new ProductMoldParam();
        param.setProductId(1L);
        param.setMoldId(101L);
        param.setCycleTimeSec(new BigDecimal("30"));
        param.setCavity(2);
        param.setYieldRate(BigDecimal.ONE);
        param.setUtilization(BigDecimal.ONE);
        long setup = registry.calculateDurationMin(
                context("SETUP", RouteOperationConstants.TM_SETUP_BASE, 100L));
        long inject = registry.calculateDurationMin(
                context("INJECT", RouteOperationConstants.TM_INJECT_A2, 100L, param));
        long post = registry.calculateDurationMin(
                context("POST_QC_PUTAWAY", RouteOperationConstants.TM_POST_UNIT, 100L));
        assertTrue(setup <= 720L, "SETUP=" + setup);
        assertTrue(inject > 0 && inject <= 720L, "INJECT=" + inject);
        assertTrue(post <= 720L, "POST=" + post);
    }

    @Test
    void setupRuleShouldTriggerChangeover() {
        assertTrue(registry.requireRule(RouteOperationConstants.RULE_SETUP_MACHINE).triggersChangeover());
        assertTrue(!registry.requireRule(RouteOperationConstants.RULE_INJECT_MACHINE).triggersChangeover());
    }

    private RouteDurationContext context(String opCode, String stdTimeModel, long batchQty) {
        return new RouteDurationContext(batchQty, 1L, null, opCode, stdTimeModel);
    }

    private RouteDurationContext context(String opCode, String stdTimeModel, long batchQty,
                                         ProductMoldParam param) {
        return new RouteDurationContext(batchQty, 1L, param, opCode, stdTimeModel);
    }
}
