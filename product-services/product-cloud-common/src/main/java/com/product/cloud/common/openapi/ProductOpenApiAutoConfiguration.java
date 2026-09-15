package com.product.cloud.common.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * OpenAPI servers 收敛（issue #3）。
 *
 * <p>springdoc 默认按当前请求解析 server url，会把服务实例的内网 IP + 直连端口写进
 * {@code servers[]}，经网关匿名可读的聚合文档等于对外泄露内部拓扑。统一改为相对路径
 * {@code "/"}：文档自描述为「经当前访问入口（网关）按路径前缀访问」，不暴露实例地址。</p>
 *
 * <p>仅当 springdoc 在 classpath 时生效（五个 servlet 服务）；服务如需自定义
 * OpenAPI（分组、鉴权声明等），定义自己的 {@code OpenAPI} Bean 即可令本配置退避。</p>
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProductOpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI productOpenApi() {
        OpenAPI openAPI = new OpenAPI();
        openAPI.setServers(List.of(new Server().url("/").description("经网关按前缀访问")));
        return openAPI;
    }
}
