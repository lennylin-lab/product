package com.product.cloud.common.config;

import com.product.cloud.common.web.GlobalServiceExceptionHandler;
import com.product.cloud.common.web.RequestContextFilter;
import com.product.cloud.common.web.SkeletonController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * product-cloud-common 自动配置。
 *
 * <p>仅对 SERVLET Web 应用生效（Gateway 为 WebFlux，引入本依赖时这些 Bean 全部跳过，
 * 只复用日志基线资源）。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProductCloudCommonAutoConfiguration {

    @Bean
    public FilterRegistrationBean<RequestContextFilter> requestContextFilterRegistration() {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>(new RequestContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        // 不能用默认名 "requestContextFilter"：Boot 3.5 的 WebMvcAutoConfiguration 已注册同名
        // 的 org.springframework.web.filter.RequestContextFilter（MockMvc 单测发现不了，
        // 真实 Tomcat 启动时同名 DynamicFilterRegistration 冲突）。
        registration.setName("productRequestContextFilter");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalServiceExceptionHandler globalServiceExceptionHandler() {
        return new GlobalServiceExceptionHandler();
    }

    @Bean
    @ConditionalOnProperty(name = "product.skeleton.info-enabled", havingValue = "true", matchIfMissing = true)
    public SkeletonController skeletonController(Environment environment) {
        return new SkeletonController(environment);
    }
}
