package com.product.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-execution 启动骨架（Phase 1，空应用，不含业务代码）。
 *
 * <p>ADR-0002：任务/资源状态事件写入、现场追溯查询；
 * 拥有 task_event/resource_status_event 表（Phase 5 迁移）。</p>
 */
@SpringBootApplication
public class ExecutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutionApplication.class, args);
    }
}
