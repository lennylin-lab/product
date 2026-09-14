package com.product.planning.service.impl;

import com.product.planning.common.utils.StringUtils;
import com.product.planning.domain.model.ChangeoverRule;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 换型时间计算器（同模/换模/换料/换色）。
 */
@Component
public class ChangeoverCalculator {

    /**
     * 根据换型规则与前后任务上下文计算换型分钟数。
     */
    public int calculateChangeoverMin(ChangeoverRule rule, MachineAssignmentSnapshot previous, MachineAssignmentSnapshot current) {
        if (rule == null || previous == null || current == null) {
            return 0;
        }
        int baseMin;
        if (previous.moldId != null && Objects.equals(previous.moldId, current.moldId)) {
            baseMin = safeMin(rule.getSameMoldTimeMin());
        } else {
            baseMin = safeMin(rule.getDifferentMoldTimeMin());
        }
        int extra = 0;
        if (isMaterialChange(previous, current)) {
            extra += safeMin(rule.getMaterialChangeExtraMin());
        }
        if (isColorChange(previous, current)) {
            extra += safeMin(rule.getColorChangeExtraMin());
        }
        return baseMin + extra;
    }

    private boolean isMaterialChange(MachineAssignmentSnapshot previous, MachineAssignmentSnapshot current) {
        if (StringUtils.isNotEmpty(previous.materialCode) || StringUtils.isNotEmpty(current.materialCode)) {
            return !StringUtils.equals(previous.materialCode, current.materialCode);
        }
        return !Objects.equals(previous.productId, current.productId);
    }

    private boolean isColorChange(MachineAssignmentSnapshot previous, MachineAssignmentSnapshot current) {
        if (StringUtils.isEmpty(previous.colorCode) && StringUtils.isEmpty(current.colorCode)) {
            return false;
        }
        if (StringUtils.equals(previous.colorCode, current.colorCode)) {
            return false;
        }
        return StringUtils.equals(previous.materialCode, current.materialCode);
    }

    private int safeMin(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    /**
     * 机台派工快照（换型比较用）。
     */
    public static final class MachineAssignmentSnapshot {
        private final Long moldId;
        private final Long productId;
        private final String materialCode;
        private final String colorCode;
        private final Long sourceTaskId;

        public MachineAssignmentSnapshot(Long moldId,
                                         Long productId,
                                         String materialCode,
                                         String colorCode,
                                         Long sourceTaskId) {
            this.moldId = moldId;
            this.productId = productId;
            this.materialCode = materialCode;
            this.colorCode = colorCode;
            this.sourceTaskId = sourceTaskId;
        }

        public Long getMoldId() {
            return moldId;
        }

        public Long getProductId() {
            return productId;
        }

        public String getMaterialCode() {
            return materialCode;
        }

        public String getColorCode() {
            return colorCode;
        }

        public Long getSourceTaskId() {
            return sourceTaskId;
        }
    }
}
