package com.product.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-planning 启动骨架（Phase 1，空应用，不含业务代码）。
 *
 * <p>ADR-0002：生产批次、工序任务、资源需求、派工、排程任务与算法；
 * 拥有 production_batch、operation_task、task_dependency、task_resource_requirement、
 * task_assignment、task_assignment_resource、schedule_job 表
 * （ADR-0005 planning_db，Phase 4 迁移）。</p>
 */
@SpringBootApplication
public class PlanningApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanningApplication.class, args);
    }
}
