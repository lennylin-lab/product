package com.product.planning.config;

/**
 * planning 域 Redis 锁 key（Phase 4；单体 RedisLockKeys.Pps.Schedule 同语义移植，
 * 前缀改为服务命名空间 planning:——ADR-0005 §4 每服务独立 key prefix）。
 */
public final class PlanningLockKeys {

    private PlanningLockKeys() {
    }

    private static final String LOCK_PREFIX = "planning:";

    /**
     * 排程相关锁（单体 Schedule 三把锁同语义）。
     */
    public static final class Schedule {
        private Schedule() {
        }

        /** 异步排程提交互斥（查重 + 建 job + 提交线程的短临界区）。 */
        public static final String SUBMIT_LOCK_KEY = LOCK_PREFIX + "pps:schedule:submit";

        /** 排程执行互斥（整个排程运行的长临界区，带看门狗续期）。 */
        public static final String EXECUTE_LOCK_KEY = LOCK_PREFIX + "pps:schedule:execute";

        /** 超时兜底扫描互斥（多实例防重复扫描）。 */
        public static final String TIMEOUT_SWEEP_LOCK_KEY = LOCK_PREFIX + "pps:schedule:timeout-sweep";
    }
}
