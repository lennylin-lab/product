-- ----------------------------------------------------------------------------
-- product-planning 数据库初始化脚本（Phase 4，ADR-0005 database per service）
-- 目标库:planning_db（共享 MySQL 8.4 实例上的独立 database）
-- 账号:planning_svc（最小权限:仅 planning_db 的 SELECT/INSERT/UPDATE/DELETE）
-- 来源:根 schema.sql 的排程 7 张表原样提取（表结构/AUTO_INCREMENT 起点未改动:
--       production_batch/operation_task/task_dependency/task_resource_requirement/
--       task_assignment/task_assignment_resource/schedule_job，baselines.md §2.1 权属映射）
-- 幂等性:整体可重复执行 —— 业务表重复执行将 planning_db 重置为已知初始空库
--         （CREATE DATABASE/USER IF NOT EXISTS + 表级 DROP/CREATE，与根 schema.sql 语义一致）
-- 权属边界:订单（customer_order/order_line）归 product-demand，产品/工艺/资源归
--         product-master-data；本库不存储跨域数据，跨域引用为业务 ID + 契约校验
--         （排程输入快照经 product-demand-api/product-master-data-api 批量契约加载）。
-- 回滚点:执行本脚本即恢复"排程库初始备份"的等价状态（implement.md Phase 4 Rollback point）
-- 注意:本脚本不修改根 schema.sql；密码请通过环境变量注入生产环境（此处默认值仅限本地开发）
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS planning_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE planning_db;

-- 独立最小权限账号（ADR-0005 §4:服务账号只能访问自己的 database）
-- 开发默认密码 planning-dev-pwd（与 .env.example 的 PLANNING_DB_PASSWORD 默认值一致）
-- 生产环境执行前先替换下行两条语句中的密码。
CREATE USER IF NOT EXISTS 'planning_svc'@'%' IDENTIFIED BY 'planning-dev-pwd';
ALTER USER 'planning_svc'@'%' IDENTIFIED BY 'planning-dev-pwd';
GRANT SELECT, INSERT, UPDATE, DELETE ON planning_db.* TO 'planning_svc'@'%';
FLUSH PRIVILEGES;

-- ----------------------------
-- 排程表（与根 schema.sql 逐字节一致）
-- ----------------------------

DROP TABLE IF EXISTS production_batch;
CREATE TABLE production_batch (
    batch_id     BIGINT(20)      NOT NULL COMMENT '批次ID',
    order_line_id BIGINT(20) DEFAULT NULL COMMENT '来源订单行ID',
    batch_qty    BIGINT(20)  DEFAULT NULL COMMENT '批次数量',
    status       VARCHAR(20) DEFAULT 'PLANNED' COMMENT '批次状态(PLANNED计划中 RELEASED已释放 IN_PROCESS执行中 DONE完成)',
    planned_start DATETIME   DEFAULT NULL COMMENT '计划开工',
    planned_end  DATETIME    DEFAULT NULL COMMENT '计划完工',
    create_time  DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time  DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (batch_id),
    KEY idx_production_batch_order_line (order_line_id),
    KEY idx_production_batch_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产批次表';

DROP TABLE IF EXISTS operation_task;
CREATE TABLE operation_task (
    task_id             BIGINT(20)      NOT NULL COMMENT '任务ID',
    batch_id            BIGINT(20)      DEFAULT NULL COMMENT '所属批次ID',
    op_code             VARCHAR(32) DEFAULT NULL COMMENT '工序(SETUP换模调机 INJECT注塑成型 POST_QC_PUTAWAY后处理&检验&入库)',
    sequence            BIGINT(20)  DEFAULT NULL COMMENT '工序顺序',
    std_duration_min    BIGINT(20)  DEFAULT NULL COMMENT '预计时长(分钟)',
    earliest_start      DATETIME    DEFAULT NULL COMMENT '最早可开始时间',
    status              VARCHAR(20) DEFAULT 'READY' COMMENT '任务状态(READY待排程 SCHEDULED已排程 RUNNING执行中 PAUSED挂起 DONE已完成 CANCELLED已取消)',
    queue_policy        VARCHAR(50) DEFAULT NULL COMMENT '排队策略',
    eligible_resource_rule VARCHAR(100) DEFAULT NULL COMMENT '可用资源规则编码',
    create_time         DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time         DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (task_id),
    KEY idx_operation_task_batch (batch_id),
    KEY idx_operation_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工序任务表';

DROP TABLE IF EXISTS task_dependency;
CREATE TABLE task_dependency (
    pre_task_id  BIGINT(20)      NOT NULL COMMENT '前置任务ID',
    post_task_id BIGINT(20)      NOT NULL COMMENT '后置任务ID',
    PRIMARY KEY (pre_task_id, post_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖表';

DROP TABLE IF EXISTS task_resource_requirement;
CREATE TABLE task_resource_requirement (
    requirement_id              BIGINT(20)      NOT NULL COMMENT '需求ID',
    task_id                     BIGINT(20)      DEFAULT NULL COMMENT '任务ID',
    resource_type               VARCHAR(20) DEFAULT NULL COMMENT '资源类型(MACHINE注塑机 MOLD模具 PERSON人员 WORKSTATION工位)',
    resource_role               VARCHAR(30) DEFAULT NULL COMMENT '资源角色',
    resource_id                 BIGINT(20)      DEFAULT NULL COMMENT '指定资源ID(为空表示按能力匹配)',
    capability_code             VARCHAR(64) DEFAULT NULL COMMENT '能力编码',
    required_count              INT(11)     DEFAULT 1 COMMENT '需求数量',
    is_mandatory                INT(1)      DEFAULT 1 COMMENT '是否硬约束(1是 0否)',
    changeover_source_resource_id BIGINT(20)      DEFAULT NULL COMMENT '换型来源资源ID',
    changeover_time_min         INT(11)     DEFAULT NULL COMMENT '换型时间(分钟)',
    create_time                 DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time                 DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (requirement_id),
    KEY idx_task_resource_requirement_task (task_id),
    KEY idx_task_resource_requirement_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务资源需求表';

DROP TABLE IF EXISTS task_assignment;
CREATE TABLE task_assignment (
    assignment_id        BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '分配ID',
    task_id              BIGINT(20)      DEFAULT NULL COMMENT '任务ID',
    machine_id           BIGINT(20)      DEFAULT NULL COMMENT '注塑机ID',
    planned_start        DATETIME    DEFAULT NULL COMMENT '计划开始时间',
    planned_end          DATETIME    DEFAULT NULL COMMENT '计划结束时间',
    sequence_on_resource BIGINT(20)  DEFAULT NULL COMMENT '资源上的顺序号(用于甘特图)',
    create_time          DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time          DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (assignment_id),
    KEY idx_task_assignment_task (task_id),
    KEY idx_task_assignment_machine (machine_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='任务分配表';

DROP TABLE IF EXISTS task_assignment_resource;
CREATE TABLE task_assignment_resource (
    assignment_resource_id BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '分配资源ID',
    assignment_id          BIGINT(20)  DEFAULT NULL COMMENT '分配ID',
    task_id                BIGINT(20)      DEFAULT NULL COMMENT '任务ID',
    resource_id            BIGINT(20)      DEFAULT NULL COMMENT '资源ID',
    resource_type          VARCHAR(20) DEFAULT NULL COMMENT '资源类型(MACHINE MOLD PERSON WORKSTATION)',
    resource_role          VARCHAR(30) DEFAULT NULL COMMENT '资源角色',
    requirement_id         BIGINT(20)      DEFAULT NULL COMMENT '对应资源需求ID',
    planned_start          DATETIME    DEFAULT NULL COMMENT '计划开始时间',
    planned_end            DATETIME    DEFAULT NULL COMMENT '计划结束时间',
    sequence_on_resource   BIGINT(20)  DEFAULT NULL COMMENT '资源上的顺序号',
    create_time            DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time            DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (assignment_resource_id),
    KEY idx_tar_assignment (assignment_id),
    KEY idx_tar_task (task_id),
    KEY idx_tar_resource (resource_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='任务分配资源表';

DROP TABLE IF EXISTS schedule_job;
CREATE TABLE schedule_job (
    job_id               BIGINT(20)      NOT NULL COMMENT '任务ID',
    job_type             VARCHAR(30) DEFAULT NULL COMMENT '任务类型',
    status               VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态(PENDING排队 RUNNING运行 SUCCESS成功 FAILED失败)',
    assignment_start     DATETIME    DEFAULT NULL COMMENT '排程基准时间',
    started_at           DATETIME    DEFAULT NULL COMMENT '开始时间',
    finished_at          DATETIME    DEFAULT NULL COMMENT '结束时间',
    total_task_count     INT(11)     DEFAULT 0 COMMENT '任务总数',
    batch_count          INT(11)     DEFAULT 0 COMMENT '批次数',
    scheduled_task_count INT(11)     DEFAULT 0 COMMENT '已排程任务数',
    processed_task_count INT(11)     DEFAULT 0 COMMENT '已处理任务数',
    progress_percent     INT(11)     DEFAULT 0 COMMENT '进度百分比',
    error_message        VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    create_time          DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time          DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (job_id),
    KEY idx_schedule_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排程任务表';

-- ----------------------------
-- 事件基础设施表（Phase 5，ADR-0004；服务自建权属表，与 execution_db/demand_db
-- 内同名表结构一致。根 schema.sql 零改动）
-- ----------------------------

-- 发件箱（batch.progress.changed 与批次状态写同事务；OutboxRelay 投递 + confirm 后标 PUBLISHED）
CREATE TABLE IF NOT EXISTS event_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行ID（投递顺序=追加顺序）',
    event_id      VARCHAR(64)  NOT NULL COMMENT '事件ID（UUID，幂等键；重放为新值）',
    event_type    VARCHAR(64)  NOT NULL COMMENT '事件类型（= routing key）',
    aggregate_id  VARCHAR(64)  DEFAULT NULL COMMENT '聚合根ID',
    correlation_id VARCHAR(64) DEFAULT NULL COMMENT '链路ID（透传上游事件/命令 traceId）',
    producer      VARCHAR(64)  NOT NULL COMMENT '生产者服务',
    payload       TEXT         COMMENT 'payload JSON',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态(PENDING待投递 PUBLISHED已投递 HALTED超限待人工)',
    retry_count   INT          NOT NULL DEFAULT 0 COMMENT '投递重试次数',
    next_retry_at DATETIME(3)  DEFAULT NULL COMMENT '下次重试时间（退避）',
    replayed_from VARCHAR(64)  DEFAULT NULL COMMENT '人工重放来源 eventId',
    occurred_at   DATETIME(3)  DEFAULT NULL COMMENT '事件发生时间（消费方单调性守卫依据）',
    created_at    DATETIME(3)  DEFAULT NULL COMMENT '写入时间',
    published_at  DATETIME(3)  DEFAULT NULL COMMENT '投递确认时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_outbox_event (event_id),
    KEY idx_event_outbox_status (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件发件箱(服务权属,非单体基线表)';

-- 消费流水（task.status.changed / resource.status.changed 的 eventId 去重与同聚合单调性守卫依据）
CREATE TABLE IF NOT EXISTS consumed_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '行ID',
    consumer_group VARCHAR(64) NOT NULL COMMENT '消费组（服务名）',
    event_id       VARCHAR(64) NOT NULL COMMENT '事件ID（去重键）',
    event_type     VARCHAR(64) DEFAULT NULL COMMENT '事件类型',
    aggregate_id   VARCHAR(64) DEFAULT NULL COMMENT '聚合根ID',
    occurred_at    DATETIME(3) DEFAULT NULL COMMENT '事件发生时间（同聚合单调性守卫）',
    outcome        VARCHAR(16) NOT NULL DEFAULT 'APPLIED' COMMENT '结果(APPLIED已应用 STALE过期丢弃 UNKNOWN聚合不存在)',
    payload        TEXT        COMMENT 'payload JSON',
    consumed_at    DATETIME(3) DEFAULT NULL COMMENT '消费时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumed_event (consumer_group, event_id),
    KEY idx_consumed_event_aggregate (consumer_group, aggregate_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件消费流水(服务权属,非单体基线表)';

-- 死信审计（消费重试耗尽 → DLX/DLQ → 审计落库后 ack；人工重放标记 replayed）
CREATE TABLE IF NOT EXISTS dead_letter_audit (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行ID',
    consumer_group VARCHAR(64)  NOT NULL COMMENT '消费组（服务名）',
    source_queue   VARCHAR(128) DEFAULT NULL COMMENT '来源队列（x-death）',
    event_id       VARCHAR(64)  DEFAULT NULL COMMENT '事件ID',
    event_type     VARCHAR(64)  DEFAULT NULL COMMENT '事件类型',
    aggregate_id   VARCHAR(64)  DEFAULT NULL COMMENT '聚合根ID',
    correlation_id VARCHAR(64)  DEFAULT NULL COMMENT '链路ID',
    payload        TEXT         COMMENT '原始 envelope JSON（毒消息为原始报文）',
    failure_reason VARCHAR(1000) DEFAULT NULL COMMENT '死信原因（x-death + 异常摘要）',
    replayed       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已人工重放',
    replay_event_id VARCHAR(64) DEFAULT NULL COMMENT '重放使用的新 eventId',
    created_at     DATETIME(3)  DEFAULT NULL COMMENT '死信落库时间',
    replayed_at    DATETIME(3)  DEFAULT NULL COMMENT '重放时间',
    PRIMARY KEY (id),
    KEY idx_dla_group_event (consumer_group, event_id),
    KEY idx_dla_replayed (consumer_group, replayed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信审计(服务权属,非单体基线表)';

-- 运维审计（对账差异/补偿/人工重放留痕）
CREATE TABLE IF NOT EXISTS ops_audit (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行ID',
    action      VARCHAR(64)  NOT NULL COMMENT '动作(RECON_DRIFT/RECON_HEAL/REPLAY_OUTBOX/REPLAY_DLQ/RECOMPUTE_BATCH_STATUS...)',
    operator    VARCHAR(64)  DEFAULT NULL COMMENT '操作者（后台任务为 system）',
    params_json TEXT         COMMENT '参数 JSON',
    result      VARCHAR(16)  DEFAULT NULL COMMENT '结果(OK/DRIFT/FAIL/NOOP...)',
    detail      TEXT         COMMENT '详情',
    created_at  DATETIME(3)  DEFAULT NULL COMMENT '时间',
    PRIMARY KEY (id),
    KEY idx_ops_audit_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维审计(服务权属,非单体基线表)';
