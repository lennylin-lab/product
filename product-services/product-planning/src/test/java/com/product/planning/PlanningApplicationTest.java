package com.product.planning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-planning 冒烟测试（离线，禁用 Nacos 注册/配置、数据源/Redis 与本地验签安全链——
 * 数据访问、锁通道与 JWKS 拉取不参与离线单测，见 spec/backend/microservices-platform.md）。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // Phase 5：离线单测关闭事件基础设施（无 Rabbit/JDBC；服务 yml 默认 enabled=true）。
        // 同时排除 Rabbit 自动装配——amqp starter 会注册 RabbitHealthIndicator，
        // 离线无 Rabbit 时 /actuator/health 会 DOWN → 503。
        "product.messaging.enabled=false",
        // 离线：无 MySQL/Redis/JWKS。排除 DataSource/MyBatis-Plus/Redis/Rabbit 自动装配，
        // 关闭本地验签安全链与 Boot 默认安全链。
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "product.security.enabled=false"
})
@AutoConfigureMockMvc
class PlanningApplicationTest {

    @Autowired
    MockMvc mockMvc;

    /** 数据访问离线替换（MyBatis-Plus 自动装配已排除，Mapper 用 mock 占位）。 */
    @MockitoBean com.product.planning.mapper.OperationTaskMapper operationTaskMapper;
    @MockitoBean com.product.planning.mapper.ProductionBatchMapper productionBatchMapper;
    @MockitoBean com.product.planning.mapper.ScheduleJobMapper scheduleJobMapper;
    @MockitoBean com.product.planning.mapper.TaskAssignmentMapper taskAssignmentMapper;
    @MockitoBean com.product.planning.mapper.TaskAssignmentResourceMapper taskAssignmentResourceMapper;
    @MockitoBean com.product.planning.mapper.TaskDependencyMapper taskDependencyMapper;

    /** 排程并发互斥锁的 Redis 通道离线替换（Redis 自动装配已排除；tryLock 恒失败=不可获锁）。 */
    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @org.junit.jupiter.api.BeforeEach
    void stubLockChannelOffline() {
        try {
            // 超时兜底扫描在离线测试中也运行：让 tryLock 平稳返回 false（锁不可得），不抛异常噪音
            org.mockito.Mockito.when(stringRedisTemplate.opsForValue()).thenReturn(
                    org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class));
            org.mockito.Mockito.when(stringRedisTemplate.opsForValue()
                    .setIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                    .thenReturn(false);
        } catch (Exception ignored) {
            // 深桩不可用时保持默认行为
        }
    }

    /** 事务模板离线替换（随 DataSource 自动装配排除；生产由 Boot 自动装配提供）。 */
    @MockitoBean org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Test
    void skeletonInfoShouldExposeServiceIdentity() throws Exception {
        mockMvc.perform(get("/skeleton/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("product-planning"))
                .andExpect(jsonPath("$.phase").value(1));
    }

    @Test
    void everyResponseShouldCarryTraceHeaderFromRequestContextFilter() throws Exception {
        mockMvc.perform(get("/skeleton/info").header("X-Trace-Id", "test-trace-id"))
                .andExpect(header().string("X-Trace-Id", "test-trace-id"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void healthEndpointShouldBeUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
