package com.product.planning.route.rule;

import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.TaskResourceRequirement;

import java.util.List;

/**
 * 路线工序可用资源规则。
 */
public interface RouteEligibleResourceRule {

    String code();

    List<TaskResourceRequirement> buildRequirements(OperationTask task, String opCode);

    boolean triggersChangeover();

    boolean requiresMachine();

    boolean requiresWorkstation();

    /**
     * 是否需要夹具资源（2026-09-15 夹具兼容增量）。
     *
     * <p>default false：既有规则零变化（child-3 计算器可据此判断而无需扫描需求行）；
     * 仅夹具感知新规则返回 true。</p>
     */
    default boolean requiresFixture() {
        return false;
    }
}
