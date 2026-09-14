package com.product.planning.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * planning 排程线程池与定时任务配置（单体 ThreadPoolConfig threadPoolTaskExecutor +
 * PpsSchedulingConfig @EnableScheduling 的服务化移植，参数与单体一致：core 50 /
 * max 200 / queue 1000 / keepAlive 300s / CallerRunsPolicy）。
 *
 * <p>消费方：TaskSchedulingCoordinator 的并行查询、ScheduleJobServiceImpl 的异步排程
 * 提交；@EnableScheduling 驱动 ScheduleJobTimeoutService 的超时兜底扫描。</p>
 */
@Configuration
@EnableScheduling
public class PlanningSchedulingConfig {

    @Bean(name = "threadPoolTaskExecutor")
    public ThreadPoolTaskExecutor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setMaxPoolSize(200);
        executor.setCorePoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(300);
        // 线程池对拒绝任务(无线程可用)的处理策略（与单体一致）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
