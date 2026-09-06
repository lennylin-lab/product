package com.product.pps.service.impl;

import com.product.domain.entity.ChangeoverRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeoverCalculatorTest {

    private final ChangeoverCalculator calculator = new ChangeoverCalculator();

    @Test
    void calculateChangeoverMinShouldUseSameMoldTimeWhenMoldUnchanged() {
        ChangeoverRule rule = buildRule(10, 60, 15, 20);
        ChangeoverCalculator.MachineAssignmentSnapshot previous = snapshot("MOLD-1", 1L, "ABS", "RED", "T0");
        ChangeoverCalculator.MachineAssignmentSnapshot current = snapshot("MOLD-1", 1L, "ABS", "RED", "T1");

        assertEquals(10, calculator.calculateChangeoverMin(rule, previous, current));
    }

    @Test
    void calculateChangeoverMinShouldAddMaterialAndColorExtras() {
        ChangeoverRule rule = buildRule(10, 60, 15, 20);
        ChangeoverCalculator.MachineAssignmentSnapshot previous = snapshot("MOLD-1", 1L, "ABS", "RED", "T0");
        ChangeoverCalculator.MachineAssignmentSnapshot current = snapshot("MOLD-2", 2L, "PP", "BLUE", "T1");

        assertEquals(75, calculator.calculateChangeoverMin(rule, previous, current));
    }

    @Test
    void calculateChangeoverMinShouldAddOnlyColorExtraWhenMaterialSame() {
        ChangeoverRule rule = buildRule(10, 60, 15, 20);
        ChangeoverCalculator.MachineAssignmentSnapshot previous = snapshot("MOLD-1", 1L, "ABS", "RED", "T0");
        ChangeoverCalculator.MachineAssignmentSnapshot current = snapshot("MOLD-2", 1L, "ABS", "BLUE", "T1");

        assertEquals(80, calculator.calculateChangeoverMin(rule, previous, current));
    }

    private ChangeoverRule buildRule(int sameMold, int differentMold, int materialExtra, int colorExtra) {
        ChangeoverRule rule = new ChangeoverRule();
        rule.setSameMoldTimeMin(sameMold);
        rule.setDifferentMoldTimeMin(differentMold);
        rule.setMaterialChangeExtraMin(materialExtra);
        rule.setColorChangeExtraMin(colorExtra);
        return rule;
    }

    private ChangeoverCalculator.MachineAssignmentSnapshot snapshot(String moldId,
                                                                    Long productId,
                                                                    String materialCode,
                                                                    String colorCode,
                                                                    String taskId) {
        return new ChangeoverCalculator.MachineAssignmentSnapshot(
                moldId, productId, materialCode, colorCode, taskId);
    }
}
