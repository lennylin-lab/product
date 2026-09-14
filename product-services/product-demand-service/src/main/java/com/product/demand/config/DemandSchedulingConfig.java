package com.product.demand.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * demand 定时任务开关（Phase 5）：驱动 OutboxRelay 的发件箱轮询投递与需求域
 * 状态对账（product-cloud-messaging + DemandReconciliationService，ADR-0004 §3/§6）。
 */
@Configuration
@EnableScheduling
public class DemandSchedulingConfig {
}
