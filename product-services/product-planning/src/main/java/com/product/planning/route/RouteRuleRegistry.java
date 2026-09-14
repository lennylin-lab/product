package com.product.planning.route;

import com.product.planning.common.constant.OperationTaskConstants;
import com.product.planning.common.constant.RouteOperationConstants;
import com.product.planning.common.exception.ServiceException;
import com.product.planning.common.utils.StringUtils;
import com.product.planning.route.model.RouteStdTimeModel;
import com.product.planning.route.rule.RouteEligibleResourceRule;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 路线工序资源规则与标准工时模型注册表。
 */
@Component
public class RouteRuleRegistry {

    private static final Map<String, String> MODEL_ALIASES = Map.of(
            "TM_INJECT", RouteOperationConstants.TM_INJECT_A2,
            "TM_SETUP", RouteOperationConstants.TM_SETUP_BASE,
            "TM_POST", RouteOperationConstants.TM_POST_UNIT);

    private final Map<String, RouteEligibleResourceRule> rulesByCode;
    private final Map<String, RouteStdTimeModel> modelsByCode;

    public RouteRuleRegistry(List<RouteEligibleResourceRule> rules, List<RouteStdTimeModel> models) {
        this.rulesByCode = indexRules(rules);
        this.modelsByCode = indexModels(models);
    }

    public Optional<RouteEligibleResourceRule> findRule(String code) {
        if (StringUtils.isEmpty(code)) {
            return Optional.empty();
        }
        return Optional.ofNullable(rulesByCode.get(code));
    }

    public RouteEligibleResourceRule requireRule(String code) {
        return findRule(code).orElseThrow(() -> new ServiceException(
                "未知的资源规则: " + code + "，合法值: " + String.join(", ", allRuleCodes())));
    }

    public Set<String> allRuleCodes() {
        return Collections.unmodifiableSet(rulesByCode.keySet());
    }

    public Optional<RouteStdTimeModel> findModel(String code) {
        String normalized = normalizeModelCode(code);
        if (StringUtils.isEmpty(normalized)) {
            return Optional.empty();
        }
        return Optional.ofNullable(modelsByCode.get(normalized));
    }

    public RouteStdTimeModel requireModel(String code) {
        return findModel(code).orElseThrow(() -> new ServiceException(
                "未知的标准工时模型: " + code + "，合法值: " + String.join(", ", allModelCodes())));
    }

    public Set<String> allModelCodes() {
        return Collections.unmodifiableSet(modelsByCode.keySet());
    }

    public String resolveDefaultModelCode(String opCode) {
        if (StringUtils.isEmpty(opCode)) {
            return RouteOperationConstants.TM_POST_UNIT;
        }
        return switch (opCode) {
            case "SETUP" -> RouteOperationConstants.TM_SETUP_BASE;
            case "INJECT" -> RouteOperationConstants.TM_INJECT_A2;
            case "POST_QC_PUTAWAY" -> RouteOperationConstants.TM_POST_UNIT;
            default -> RouteOperationConstants.TM_POST_UNIT;
        };
    }

    public long calculateDurationMin(RouteDurationContext context) {
        String modelCode = StringUtils.isEmpty(context.stdTimeModel())
                ? resolveDefaultModelCode(context.opCode())
                : context.stdTimeModel();
        Optional<RouteStdTimeModel> model = findModel(modelCode);
        if (model.isPresent()) {
            return model.get().calculateDurationMin(context);
        }
        if ("INJECT".equals(context.opCode())) {
            return requireModel(RouteOperationConstants.TM_INJECT_A2).calculateDurationMin(context);
        }
        int opIndex = OperationTaskConstants.OP_CODE.indexOf(context.opCode());
        long baseDuration = opIndex >= 0
                ? OperationTaskConstants.STD_DURATION_MIM.get(opIndex)
                : OperationTaskConstants.STD_DURATION_MIM.get(1);
        return baseDuration * context.batchQty();
    }

    private Map<String, RouteEligibleResourceRule> indexRules(List<RouteEligibleResourceRule> rules) {
        Map<String, RouteEligibleResourceRule> indexed = new HashMap<>();
        if (rules != null) {
            for (RouteEligibleResourceRule rule : rules) {
                if (rule == null || StringUtils.isEmpty(rule.code())) {
                    continue;
                }
                indexed.put(rule.code(), rule);
            }
        }
        return Map.copyOf(indexed);
    }

    private Map<String, RouteStdTimeModel> indexModels(List<RouteStdTimeModel> models) {
        Map<String, RouteStdTimeModel> indexed = new HashMap<>();
        if (models != null) {
            for (RouteStdTimeModel model : models) {
                if (model == null || StringUtils.isEmpty(model.code())) {
                    continue;
                }
                indexed.put(model.code(), model);
            }
        }
        return Map.copyOf(indexed);
    }

    private String normalizeModelCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return code;
        }
        return MODEL_ALIASES.getOrDefault(code, code);
    }
}
