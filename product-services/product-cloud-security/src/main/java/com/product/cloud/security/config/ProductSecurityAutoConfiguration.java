package com.product.cloud.security.config;

import com.product.cloud.security.auth.JwtAuthenticationFilter;
import com.product.cloud.security.auth.PermissionService;
import com.product.cloud.security.auth.ProductAuthenticationEntryPoint;
import com.product.cloud.security.jwks.JwksKeyHolder;
import com.product.cloud.security.jwt.JwtVerifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端本地验签安全链（ADR-0003 决策 4：两层校验的服务侧）。
 *
 * <p>行为对齐单体 {@code product-auth} SecurityConfig：无状态、CSRF 关闭、
 * 匿名端点 permitAll、其余 anyRequest authenticated、认证失败走统一 401 错误体、
 * 方法级 {@code @PreAuthorize("@ss.hasPermi(...)")} 开启。JWT 过滤器做本地验签。</p>
 *
 * <p>与单体安全配置的差异（有据可查的收敛）：</p>
 * <ul>
 *   <li>CORS 由网关统一承担（外部请求只进网关），服务侧不再配置 CorsFilter；</li>
 *   <li>/druid/**、静态资源（/profile/** 等）不在此放行——服务不暴露这些资产；</li>
 *   <li>/logout、/register 在基线中冻结为"不存在"，仅保持 permitAll 与单体一致（无对应 handler）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
// 仅 SERVLET 应用装配（Gateway 为 WebFlux，无 servlet 类路径，条件评估阶段即跳过，
// 避免对 servlet 类型方法签名的内省失败）；网关复用本模块的纯 Java 验签核心。
@ConditionalOnClass(jakarta.servlet.Filter.class)
@ConditionalOnProperty(prefix = "product.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@EnableConfigurationProperties(ProductSecurityProperties.class)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class ProductSecurityAutoConfiguration {

    /** 与单体一致的基线匿名端点 + 微服务体系新增内建资产（JWKS、健康检查、文档）。 */
    static final List<String> BASE_PERMIT_URLS = List.of(
            // 单体 SecurityConfig 硬编码匿名端点（/register 冻结为不存在，仅保持放行语义）
            "/login", "/register", "/captchaImage",
            // JWT 公钥发布（ADR-0003 决策 3）：网关与服务启动/轮换时需要匿名拉取
            "/jwks",
            // 文档与健康检查（单体放行 swagger；健康检查为运维基线）
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/actuator/health", "/actuator/health/**");

    @Bean
    public JwtVerifier jwtVerifier(ProductSecurityProperties properties) {
        return new JwtVerifier(properties.getClockSkewSeconds());
    }

    @Bean
    public JwksKeyHolder jwksKeyHolder(ProductSecurityProperties properties) {
        return new JwksKeyHolder(properties.getJwksUri());
    }

    @Bean
    public ProductAuthenticationEntryPoint productAuthenticationEntryPoint() {
        return new ProductAuthenticationEntryPoint();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier jwtVerifier,
                                                           JwksKeyHolder keyHolder,
                                                           ProductSecurityProperties properties) {
        return new JwtAuthenticationFilter(jwtVerifier, keyHolder, properties.getHeader());
    }

    @Bean
    public SecurityFilterChain productSecurityFilterChain(HttpSecurity httpSecurity,
                                                          JwtAuthenticationFilter jwtAuthenticationFilter,
                                                          ProductAuthenticationEntryPoint entryPoint,
                                                          ProductSecurityProperties properties) throws Exception {
        List<String> permitUrls = new ArrayList<>(BASE_PERMIT_URLS);
        if (properties.getPermitUrls() != null) {
            permitUrls.addAll(properties.getPermitUrls());
        }
        return httpSecurity
                // 与单体一致：无状态 token，不使用 session/CSRF
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .cacheControl(cache -> cache.disable())
                        .frameOptions(options -> options.sameOrigin()))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(entryPoint))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    for (String url : permitUrls) {
                        requests.requestMatchers(url).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean("ss")
    public PermissionService permissionService() {
        return new PermissionService();
    }
}
