package com.product.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * product-planning 排程与计划域服务（Phase 4 迁移）。
 *
 * <p>ADR-0002：生产批次、工序任务、资源需求、派工、排程任务与算法；
 * 拥有 production_batch、operation_task、task_dependency、task_resource_requirement、
 * task_assignment、task_assignment_resource、schedule_job 表
 * （ADR-0005 planning_db）。</p>
 *
 * <p>Feign 契约扫描范围：{@code com.product.demand.api}（订单/订单行批量 + 数量预占用）
 * 与 {@code com.product.masterdata.api}（产品/路线/资源/日历/换型规则批量）——
 * 排程版本化输入快照与拆批预占的调用通道（SchedulingSnapshotLoader /
 * ProductionBatchServiceImpl）；超时/重试语义见 application.yml。</p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = {"com.product.demand.api", "com.product.masterdata.api"})
public class PlanningApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanningApplication.class, args);
    }
}
