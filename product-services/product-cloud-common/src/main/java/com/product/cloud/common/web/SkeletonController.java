package com.product.cloud.common.web;

import org.slf4j.MDC;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 1 骨架诊断端点：{@code GET /skeleton/info}。
 *
 * <p>非业务端点，仅用于平台骨架验证：</p>
 * <ul>
 *   <li>标识应答服务（经 Gateway 路由后用于确认「哪个服务响应了」）；</li>
 *   <li>回显 Nacos 配置加载标记（{@code product.skeleton.config-marker} /
 *       {@code product.skeleton.shared-marker}），作为 namespace/group/Data ID
 *       配置隔离的冒烟证据；</li>
 *   <li>回显当前请求 traceId/requestId，作为 observability 冒烟证据。</li>
 * </ul>
 *
 * <p>实现说明：本类经自动配置 {@code @Bean} 注册（非组件扫描）。Spring MVC 的
 * {@code RequestMappingHandlerMapping} 只把类级别带 {@code @Controller}/{@code @RequestMapping}
 * 注解的 Bean 识别为处理器，因此类上必须保留 {@code @RestController}，否则 {@code /skeleton/info}
 * 会落到 404。</p>
 *
 * <p>可通过 {@code product.skeleton.info-enabled=false} 关闭。</p>
 */
@org.springframework.web.bind.annotation.RestController
public class SkeletonController {

    private final Environment environment;

    public SkeletonController(Environment environment) {
        this.environment = environment;
    }

    @org.springframework.web.bind.annotation.GetMapping("/skeleton/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", environment.getProperty("spring.application.name"));
        info.put("phase", 1);
        info.put("configMarker", environment.getProperty("product.skeleton.config-marker", "unset"));
        info.put("sharedMarker", environment.getProperty("product.skeleton.shared-marker", "unset"));
        info.put("traceId", MDC.get("traceId"));
        info.put("requestId", MDC.get("requestId"));
        return info;
    }
}
