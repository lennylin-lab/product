-- ----------------------------------------------------------------------------
-- product-execution 数据库初始化脚本（Phase 5，ADR-0005 database per service）
-- 目标库:execution_db（共享 MySQL 8.4 实例上的独立 database）
-- 账号:execution_svc（最小权限:仅 execution_db 的 SELECT/INSERT/UPDATE/DELETE）
-- 来源:根 schema.sql 的执行 2 张表原样提取（task_event/resource_status_event，
--       baselines.md §2.1 权属映射；DDL 与 AUTO_INCREMENT 语义未改动）
-- 服务权属表（Phase 5，ADR-0004/0005，非单体基线表）:event_outbox（发件箱）/
--       consumed_event（消费去重，预留本服务未来作为消费方）/dead_letter_audit
--       （死信审计）/ops_audit（对账/补偿/重放审计）——事件基础设施表为服务自建
--       权属表，根 schema.sql 零改动
-- 幂等性:整体可重复执行 —— 业务表重复执行将 execution_db 重置为已知初始空库
--         （CREATE DATABASE/USER IF NOT EXISTS + 表级 DROP/CREATE，与根 schema.sql 语义一致）
-- 权属边界:工序任务/批次归 product-planning，订单/订单行归 product-demand；
--         本库不存储跨域数据，任务存在性/派工机台经 product-planning-api 只读契约校验
-- 回滚点:执行本脚本即恢复"执行库初始备份"的等价状态（implement.md Phase 5 Rollback point）
-- 注意:本脚本不修改根 schema.sql；密码请通过环境变量注入生产环境（此处默认值仅限本地开发）
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS execution_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE execution_db;

-- 独立最小权限账号（ADR-0005 §4:服务账号只能访问自己的 database）
-- 开发默认密码 execution-dev-pwd（与 .env.example 的 EXECUTION_DB_PASSWORD 默认值一致）
-- 生产环境执行前先替换下行两条语句中的密码。
CREATE USER IF NOT EXISTS 'execution_svc'@'%' IDENTIFIED BY 'execution-dev-pwd';
ALTER USER 'execution_svc'@'%' IDENTIFIED BY 'execution-dev-pwd';
GRANT SELECT, INSERT, UPDATE, DELETE ON execution_db.* TO 'execution_svc'@'%';
FLUSH PRIVILEGES;

-- ----------------------------
-- 生产执行表（与根 schema.sql 逐字节一致）
-- ----------------------------

DROP TABLE IF EXISTS task_event;
CREATE TABLE task_event (
    event_id    BIGINT(20)      NOT NULL COMMENT '事件ID',
    task_id     BIGINT(20)      DEFAULT NULL COMMENT '任务ID',
    event_type  VARCHAR(20) DEFAULT NULL COMMENT '事件类型(START任务开始 PAUSE任务暂停 RESUME任务恢复 FINISH任务完工 EXCEPTION任务异常)',
    event_time  DATETIME    DEFAULT NULL COMMENT '事件时间',
    operator_id BIGINT(20)      DEFAULT NULL COMMENT '操作人ID',
    resource_id BIGINT(20)      DEFAULT NULL COMMENT '发生事件的资源',
    qty_good    BIGINT(20)  DEFAULT NULL COMMENT '良品数量',
    qty_bad     BIGINT(20)  DEFAULT NULL COMMENT '不良数量',
    reason_code VARCHAR(50) DEFAULT NULL COMMENT '原因编码',
    remark      VARCHAR(500) DEFAULT '' COMMENT '备注',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (event_id),
    KEY idx_task_event_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务事件表';

DROP TABLE IF EXISTS resource_status_event;
CREATE TABLE resource_status_event (
    event_id        BIGINT(20)      NOT NULL COMMENT '事件ID',
    resource_id     BIGINT(20)      DEFAULT NULL COMMENT '资源ID',
    time            DATETIME    DEFAULT NULL COMMENT '发生时间',
    from_status     VARCHAR(20) DEFAULT NULL COMMENT '原状态',
    to_status       VARCHAR(20) DEFAULT NULL COMMENT '新状态',
    reason_code     VARCHAR(50) DEFAULT NULL COMMENT '原因编码',
    related_task_id BIGINT(20)      DEFAULT NULL COMMENT '关联任务ID',
    create_time     DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time     DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (event_id),
    KEY idx_resource_status_event_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源状态事件表';

-- ----------------------------
-- 事件基础设施表（Phase 5，ADR-0004；服务自建权属表，与 planning_db/demand_db
-- 内同名表结构一致）
-- ----------------------------

-- 发件箱（业务写与 outbox 写同事务；OutboxRelay 投递 + publisher confirm 后标 PUBLISHED）
CREATE TABLE IF NOT EXISTS event_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行ID（投递顺序=追加顺序）',
    event_id      VARCHAR(64)  NOT NULL COMMENT '事件ID（UUID，幂等键；重放为新值）',
    event_type    VARCHAR(64)  NOT NULL COMMENT '事件类型（= routing key）',
    aggregate_id  VARCHAR(64)  DEFAULT NULL COMMENT '聚合根ID',
    correlation_id VARCHAR(64) DEFAULT NULL COMMENT '链路ID（透传命令 traceId）',
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

-- 消费流水（以 eventId 幂等去重；本服务当前为纯生产者，表随基础设施预留）
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
    action      VARCHAR(64)  NOT NULL COMMENT '动作(RECON_DRIFT/RECON_HEAL/REPLAY_OUTBOX/REPLAY_DLQ/ADJUST_ALLOCATION...)',
    operator    VARCHAR(64)  DEFAULT NULL COMMENT '操作者（后台任务为 system）',
    params_json TEXT         COMMENT '参数 JSON',
    result      VARCHAR(16)  DEFAULT NULL COMMENT '结果(OK/DRIFT/FAIL...)',
    detail      TEXT         COMMENT '详情',
    created_at  DATETIME(3)  DEFAULT NULL COMMENT '时间',
    PRIMARY KEY (id),
    KEY idx_ops_audit_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维审计(服务权属,非单体基线表)';

-- ----------------------------
-- 运行时/审计表重置（issue #14：IF NOT EXISTS 表不随重跑重建，须显式清空，
-- 保持「可重复执行 = 重置为种子空库」语义）
-- ----------------------------
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE event_outbox;
TRUNCATE TABLE consumed_event;
TRUNCATE TABLE dead_letter_audit;
TRUNCATE TABLE ops_audit;
SET FOREIGN_KEY_CHECKS = 1;
