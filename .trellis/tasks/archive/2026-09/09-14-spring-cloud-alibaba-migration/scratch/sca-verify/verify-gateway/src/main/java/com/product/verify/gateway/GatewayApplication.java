package com.product.verify.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /** Sentinel gateway flow rule bound to route id 'provider' (QPS 5). */
    @PostConstruct
    public void loadGatewayRules() {
        Set<GatewayFlowRule> rules = Set.of(new GatewayFlowRule("provider").setCount(5));
        GatewayRuleManager.loadRules(rules);
    }
}
