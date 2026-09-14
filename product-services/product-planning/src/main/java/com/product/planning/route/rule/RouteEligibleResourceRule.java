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
}
