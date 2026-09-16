package com.product.planning.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 综合成本权重配置绑定测试（离线，无 Spring 上下文）：
 * 默认值（业务可读基准 1/30/60/0）、yml 形态 kebab-case 覆盖、env 形态
 * {@code PRODUCT_PPS_SCHEDULE_COST_MODEL_*} 覆盖、负值启动失败（绑定校验）、0 合法（关闭因子）。
 */
class ProductCostModelPropertiesTest {

    private static final String PREFIX = "product.pps.schedule.cost-model";

    @Test
    void defaultsShouldBeBusinessReadableBaseline() {
        ProductCostModelProperties props = new ProductCostModelProperties();
        assertEquals(1, props.getSetupWeight(), "换型时间权重默认 1（与历史行为等价）");
        assertEquals(30, props.getChangeoverCountPenalty(), "一次额外换模 ≈ 30 分钟等效成本");
        assertEquals(60, props.getCrossShiftPenalty(), "一次跨班 ≈ 60 分钟等效成本");
        assertEquals(0, props.getEnergyWeight(), "能耗为 KD1 预留槽位，默认 0");
        assertDoesNotThrow(props::afterPropertiesSet);
    }

    @Test
    void kebabCaseKeysShouldOverrideDefaults() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                PREFIX + ".setup-weight", "2",
                PREFIX + ".changeover-count-penalty", "45",
                PREFIX + ".cross-shift-penalty", "120",
                PREFIX + ".energy-weight", "5"));
        ProductCostModelProperties bound = new Binder(source)
                .bind(PREFIX, Bindable.of(ProductCostModelProperties.class))
                .get();
        assertEquals(2, bound.getSetupWeight());
        assertEquals(45, bound.getChangeoverCountPenalty());
        assertEquals(120, bound.getCrossShiftPenalty());
        assertEquals(5, bound.getEnergyWeight());
        assertDoesNotThrow(bound::afterPropertiesSet);
    }

    @Test
    void environmentVariableOverrideShouldBind() {
        SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("test-env", Map.of(
                "PRODUCT_PPS_SCHEDULE_COST_MODEL_SETUP_WEIGHT", "3",
                "PRODUCT_PPS_SCHEDULE_COST_MODEL_CROSS_SHIFT_PENALTY", "90"));
        Binder binder = new Binder(ConfigurationPropertySources.from(env));
        ProductCostModelProperties bound = binder
                .bind(PREFIX, Bindable.of(ProductCostModelProperties.class))
                .get();
        assertEquals(3, bound.getSetupWeight());
        assertEquals(90, bound.getCrossShiftPenalty());
        assertEquals(30, bound.getChangeoverCountPenalty(), "未覆盖的键保持默认值");
        assertEquals(0, bound.getEnergyWeight());
    }

    @Test
    void negativeWeightShouldFailStartupValidation() {
        assertNegativeRejected(props -> props.setSetupWeight(-1));
        assertNegativeRejected(props -> props.setChangeoverCountPenalty(-30));
        assertNegativeRejected(props -> props.setCrossShiftPenalty(-60));
        assertNegativeRejected(props -> props.setEnergyWeight(-1));
    }

    @Test
    void zeroWeightShouldBeAllowedToDisableFactor() {
        ProductCostModelProperties props = new ProductCostModelProperties();
        props.setChangeoverCountPenalty(0);
        props.setCrossShiftPenalty(0);
        props.setEnergyWeight(0);
        assertDoesNotThrow(props::afterPropertiesSet, "权重为 0 表示关闭该因子，合法");
    }

    private void assertNegativeRejected(java.util.function.Consumer<ProductCostModelProperties> setter) {
        ProductCostModelProperties props = new ProductCostModelProperties();
        setter.accept(props);
        IllegalStateException ex = assertThrows(IllegalStateException.class, props::afterPropertiesSet,
                "负权重必须启动失败（绑定校验），不允许静默取 0");
        assertEquals(true, ex.getMessage().contains("权重不允许负值"));
    }
}
