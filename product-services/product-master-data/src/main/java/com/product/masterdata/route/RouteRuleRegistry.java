package com.product.masterdata.route;

import com.product.masterdata.common.constant.RouteOperationConstants;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.common.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 路线工序资源规则与标准工时模型注册表（校验职责，Phase 3）。
 *
 * <p>单体同名的 {@code com.product.pps.route.RouteRuleRegistry} 同时承担校验与排程工时计算；
 * 计算依赖排程域模型（RouteDurationContext/OperationTask 常量），归 product-planning（Phase 4）。
 * 本类只保留路线创建/更新/启用时对 {@code eligible_resource_rule}/{@code std_time_model}
 * 编码的合法性校验：合法编码集合与单体一致（3 条资源规则、3 个标准工时模型 + 别名），
 * 错误文案逐字一致（含 allRuleCodes/allModelCodes 的拼接顺序——与单体同为
 * HashMap + Map.copyOf 的确定性哈希序）。</p>
 */
@Component
public class RouteRuleRegistry {

    private static final Map<String, String> MODEL_ALIASES = Map.of(
            "TM_INJECT", RouteOperationConstants.TM_INJECT_A2,
            "TM_SETUP", RouteOperationConstants.TM_SETUP_BASE,
            "TM_POST", RouteOperationConstants.TM_POST_UNIT);

    private static final Set<String> RULE_CODES = Set.of(
            RouteOperationConstants.RULE_SETUP_MACHINE,
            RouteOperationConstants.RULE_INJECT_MACHINE,
            RouteOperationConstants.RULE_POST_WORKSTATION);

    private final Map<String, Boolean> rulesByCode;
    private final Map<String, Boolean> modelsByCode;

    public RouteRuleRegistry() {
        this.rulesByCode = index(RULE_CODES);
        this.modelsByCode = index(Set.of(
                RouteOperationConstants.TM_SETUP_BASE,
                RouteOperationConstants.TM_INJECT_A2,
                RouteOperationConstants.TM_POST_UNIT));
    }

    public Optional<String> findRule(String code) {
        if (StringUtils.isEmpty(code)) {
            return Optional.empty();
        }
        return rulesByCode.containsKey(code) ? Optional.of(code) : Optional.empty();
    }

    public String requireRule(String code) {
        return findRule(code).orElseThrow(() -> new ServiceException(
                "未知的资源规则: " + code + "，合法值: " + String.join(", ", allRuleCodes())));
    }

    public Set<String> allRuleCodes() {
        return Collections.unmodifiableSet(rulesByCode.keySet());
    }

    public Optional<String> findModel(String code) {
        String normalized = normalizeModelCode(code);
        if (StringUtils.isEmpty(normalized)) {
            return Optional.empty();
        }
        return modelsByCode.containsKey(normalized) ? Optional.of(normalized) : Optional.empty();
    }

    public String requireModel(String code) {
        return findModel(code).orElseThrow(() -> new ServiceException(
                "未知的标准工时模型: " + code + "，合法值: " + String.join(", ", allModelCodes())));
    }

    public Set<String> allModelCodes() {
        return Collections.unmodifiableSet(modelsByCode.keySet());
    }

    private String normalizeModelCode(String code) {
        if (StringUtils.isEmpty(code)) {
            return code;
        }
        return MODEL_ALIASES.getOrDefault(code, code);
    }

    private Map<String, Boolean> index(Set<String> codes) {
        Map<String, Boolean> indexed = new HashMap<>();
        if (codes != null) {
            for (String code : codes) {
                if (StringUtils.isEmpty(code)) {
                    continue;
                }
                indexed.put(code, Boolean.TRUE);
            }
        }
        return Map.copyOf(indexed);
    }
}
