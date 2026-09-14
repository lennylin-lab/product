package com.product.masterdata.route;

import com.product.masterdata.common.constant.RouteOperationConstants;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.common.utils.StringUtils;
import com.product.masterdata.domain.entity.RouteOperation;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 路线工序定义校验器（单体 product-pps RouteOperationValidator 逐字移植，包路径服务内化）。
 */
@Component
public class RouteOperationValidator {

    private final RouteRuleRegistry routeRuleRegistry;

    public RouteOperationValidator(RouteRuleRegistry routeRuleRegistry) {
        this.routeRuleRegistry = routeRuleRegistry;
    }

    public void validate(List<RouteOperation> operations) {
        if (CollectionUtils.isEmpty(operations)) {
            throw new ServiceException("工艺路线至少包含一道工序");
        }
        Set<Integer> sequences = new HashSet<>();
        for (RouteOperation operation : operations) {
            if (operation == null) {
                throw new ServiceException("工序定义不能为空");
            }
            if (StringUtils.isEmpty(operation.getOpCode())) {
                throw new ServiceException("工序编码不能为空");
            }
            Integer sequence = operation.getSequence();
            if (sequence == null || sequence <= 0) {
                throw new ServiceException("工序顺序必须大于 0: opCode=" + operation.getOpCode());
            }
            if (!sequences.add(sequence)) {
                throw new ServiceException("工序顺序重复: sequence=" + sequence);
            }
            routeRuleRegistry.requireRule(operation.getEligibleResourceRule());
            routeRuleRegistry.requireModel(operation.getStdTimeModel());
            validateQueuePolicy(operation.getQueuePolicy());
        }
    }

    private void validateQueuePolicy(String queuePolicy) {
        if (StringUtils.isEmpty(queuePolicy)) {
            throw new ServiceException("排队策略不能为空，合法值: "
                    + String.join(", ", RouteOperationConstants.QUEUE_POLICIES));
        }
        if (!RouteOperationConstants.QUEUE_POLICIES.contains(queuePolicy)) {
            throw new ServiceException("未知的排队策略: " + queuePolicy + "，合法值: "
                    + String.join(", ", RouteOperationConstants.QUEUE_POLICIES));
        }
    }
}
