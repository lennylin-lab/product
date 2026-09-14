package com.product.planning.service.impl;

import com.product.planning.domain.model.ChangeoverRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeoverCalculatorTest {

    private final ChangeoverCalculator calculator = new ChangeoverCalculator();

    @Test
    void calculateChangeoverMinShouldUseSameMoldTimeWhenMoldUnchanged() {
        ChangeoverRule rule = buildRule(10, 60, 15, 20);
        ChangeoverCalculator.MachineAssignmentSnapshot previous = snapshot(201L, 1L, "ABS", "RED", 500L);
        ChangeoverCalculator.MachineAssignmentSnapshot current = snapshot(201L, 1L, "ABS", "RED", 501L);

        assertEquals(10, calculator.calculateChangeoverMin(rule, previous, current));
    }

    @Test
    void calculateChangeoverMinShouldAddMaterialAndColorExtras() {
        ChangeoverRule rule = buildRule(10, 60, 15, 20);
        ChangeoverCalculator.MachineAssignmentSnapshot previous = snapshot(201L, 1L, "ABS", "RED", 500L);
        ChangeoverCalculator.MachineAssignmentSnapshot current = snapshot(202L, 2L, "PP", "BLUE", 501L);

        assertEquals(75, calculator.calculateChangeoverMin(rule, previous, current));
    }

    @Test
    void calculateChangeoverMinShouldAddOnlyColorExtraWhenMaterialSame() {
        ChangeoverRule rule = buildRule(10, 60, 15, 20);
        ChangeoverCalculator.MachineAssignmentSnapshot previous = snapshot(201L, 1L, "ABS", "RED", 500L);
        ChangeoverCalculator.MachineAssignmentSnapshot current = snapshot(202L, 1L, "ABS", "BLUE", 501L);

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

    private ChangeoverCalculator.MachineAssignmentSnapshot snapshot(Long moldId,
                                                                    Long productId,
                                                                    String materialCode,
                                                                    String colorCode,
                                                                    Long taskId) {
        return new ChangeoverCalculator.MachineAssignmentSnapshot(
                moldId, productId, materialCode, colorCode, taskId);
    }
}
