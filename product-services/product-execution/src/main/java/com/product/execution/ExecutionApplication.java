package com.product.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * product-execution 执行域服务（Phase 5 迁移完成）。
 *
 * <p>ADR-0002：任务/资源状态事件写入、现场追溯查询；拥有 task_event、
 * resource_status_event 表（ADR-0005 execution_db，独立最小权限账号 execution_svc）。</p>
 *
 * <p>Phase 5（ADR-0004）：/execute/event 契约与单体一致（标准 CRUD + 开工/暂停/
 * 恢复/完工）；任务事件经事务内 Outbox 由后台发布器投递 RabbitMQ
 * （exchange=execution.events，routing key=eventType，publisher confirm），任务/
 * 批次/订单状态推进由 product-planning / product-demand 消费事件各自完成——
 * 跨域不再同步调用。任务存在性/派工机台经 planning 只读契约
 * {@code PlanningTaskApi} 获取（替代单体进程内跨模块查询）。</p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = {"com.product.planning.api"})
public class ExecutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutionApplication.class, args);
    }
}
