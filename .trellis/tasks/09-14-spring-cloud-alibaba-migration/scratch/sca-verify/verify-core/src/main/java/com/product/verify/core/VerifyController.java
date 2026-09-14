package com.product.verify.core;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerifyController {

    private final ProviderClient providerClient;

    @Value("${verify.greeting:CONFIG_NOT_LOADED}")
    private String greeting;

    public VerifyController(ProviderClient providerClient) {
        this.providerClient = providerClient;
    }

    /** 1. OpenFeign + Nacos Discovery + LoadBalancer: call verify-provider through the registry. */
    @GetMapping("/feign/hello")
    public String feignHello(@RequestParam(defaultValue = "world") String name) {
        return providerClient.hello(name);
    }

    /** 2. Nacos Config: value must come from remote config verify-core.properties. */
    @GetMapping("/config/greeting")
    public String configGreeting() {
        return greeting;
    }

    /** 3. Sentinel: QPS-2 flow rule on resource 'sentinel-ping'. */
    @GetMapping("/sentinel/ping")
    @SentinelResource(value = "sentinel-ping", blockHandler = "pingBlocked")
    public String sentinelPing() {
        return "pong";
    }

    /** blockHandler signature: original params + BlockException. */
    public String pingBlocked(BlockException ex) {
        return "blocked-by-sentinel";
    }

    @PostConstruct
    public void loadSentinelRules() {
        FlowRule rule = new FlowRule();
        rule.setResource("sentinel-ping");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(2);
        FlowRuleManager.loadRules(List.of(rule));
    }
}
