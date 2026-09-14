package com.product.execution.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * execution 定时任务开关（Phase 5）：驱动 OutboxRelay 的发件箱轮询投递
 * （product-cloud-messaging，ADR-0004 §3）。
 */
@Configuration
@EnableScheduling
public class ExecutionSchedulingConfig {
}
