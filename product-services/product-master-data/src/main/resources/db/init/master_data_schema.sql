-- ----------------------------------------------------------------------------
-- product-master-data 数据库初始化脚本（Phase 3，ADR-0005 database per service）
-- 目标库:master_data_db（共享 MySQL 8.4 实例上的独立 database）
-- 账号:master_data_svc（最小权限:仅 master_data_db 的 SELECT/INSERT/UPDATE/DELETE）
-- 来源:根 schema.sql 的主数据 11 张表原样提取（表结构与 AUTO 起点未改动，
--       含 product_route.active_flag 生成列与 uk_product_route_active 唯一约束）
-- 幂等性:整体可重复执行 —— 业务表重复执行将 master_data_db 重置为已知初始空库
--         （CREATE DATABASE/USER IF NOT EXISTS + 表级 DROP/CREATE，与根 schema.sql 语义一致）
-- 注意:master_data_data_version 用 CREATE TABLE IF NOT EXISTS 刻意不随重跑清零
--         （snapshotVersion 必须单调递增，重置会破坏快照漂移检测），属唯一例外。
-- 回滚点:执行本脚本即恢复"主数据库初始备份"的等价状态（implement.md Phase 3 Rollback point）
-- 额外表:master_data_data_version 为本服务自有权属表（Phase 3 新增的数据版本计数，
--         服务间契约快照版本号来源，见 ADR-0005"服务自建 outbox/去重表"同款权属逻辑），
--         不属于单体 32 表基线，根 schema.sql 零改动。
-- 额外表:fixture 为本服务新增的夹具扩展表（2026-09-15 夹具建模任务；扩展表主键 =
--         resource.resource_id，与 machine/mold 同款约定），同样不属于单体 32 表基线，
--         根 schema.sql 零改动。
-- 注意:本脚本不修改根 schema.sql；密码请通过环境变量注入生产环境（此处默认值仅限本地开发）
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS master_data_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE master_data_db;

-- 独立最小权限账号（ADR-0005 §4:服务账号只能访问自己的 database）
-- 开发默认密码 master-data-dev-pwd（与 .env.example 的 MASTER_DATA_DB_PASSWORD 默认值一致）
-- 生产环境执行前先替换下行两条语句中的密码。
CREATE USER IF NOT EXISTS 'master_data_svc'@'%' IDENTIFIED BY 'master-data-dev-pwd';
ALTER USER 'master_data_svc'@'%' IDENTIFIED BY 'master-data-dev-pwd';
GRANT SELECT, INSERT, UPDATE, DELETE ON master_data_db.* TO 'master_data_svc'@'%';
FLUSH PRIVILEGES;

-- ----------------------------
-- 主数据表（与根 schema.sql 逐字段一致）
-- ----------------------------

DROP TABLE IF EXISTS resource;
CREATE TABLE resource (
    resource_id   BIGINT(20)       NOT NULL                COMMENT '资源ID',
    resource_type VARCHAR(20)  DEFAULT ''              COMMENT '资源类型(MACHINE注塑机 MOLD模具 PERSON人员 WORKSTATION工位 FIXTURE夹具)',
    name          VARCHAR(100) DEFAULT ''              COMMENT '资源名称',
    status        VARCHAR(20)  DEFAULT 'AVAILABLE'     COMMENT '资源状态(AVAILABLE可用 BUSY忙碌 DOWN故障 MAINTENANCE保养中 OFFSHIFT离班)',
    calendar_id   BIGINT(20)   DEFAULT NULL            COMMENT '班次日历ID',
    org_unit      VARCHAR(100) DEFAULT ''              COMMENT '车间/班组/部门',
    create_time   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (resource_id),
    KEY idx_resource_type (resource_type),
    KEY idx_resource_calendar (calendar_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协同资源表';

DROP TABLE IF EXISTS machine;
CREATE TABLE machine (
    machine_id            BIGINT(20)      NOT NULL COMMENT '机台ID(与resource.resource_id对应)',
    tonnage               INT(11)     DEFAULT NULL COMMENT '锁模力',
    default_setup_time_min INT(11)    DEFAULT NULL COMMENT '默认换模基准时间(分钟)',
    PRIMARY KEY (machine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注塑机表';

DROP TABLE IF EXISTS mold;
CREATE TABLE mold (
    mold_id       BIGINT(20)      NOT NULL COMMENT '模具ID(与resource.resource_id对应)',
    mold_code     VARCHAR(64) DEFAULT '' COMMENT '模具业务编号',
    cavity        INT(11)     DEFAULT NULL COMMENT '型腔数',
    mold_status   VARCHAR(20) DEFAULT 'AVAILABLE' COMMENT '模具状态(AVAILABLE可用 IN_USE使用中 REPAIR维修中 MAINTENANCE保养中)',
    next_maint_due DATE       DEFAULT NULL COMMENT '下次保养到期日',
    PRIMARY KEY (mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模具表';

DROP TABLE IF EXISTS fixture;
CREATE TABLE fixture (
    fixture_id   BIGINT(20)  NOT NULL COMMENT '夹具ID(与resource.resource_id对应)',
    fixture_code VARCHAR(64) DEFAULT '' COMMENT '夹具业务编号',
    PRIMARY KEY (fixture_id),
    KEY idx_fixture_code (fixture_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='夹具表';

DROP TABLE IF EXISTS machine_mold_compatibility;
CREATE TABLE machine_mold_compatibility (
    machine_id    BIGINT(20)      NOT NULL COMMENT '机台ID',
    mold_id       BIGINT(20)      NOT NULL COMMENT '模具ID',
    is_compatible INT(1)      DEFAULT 1 COMMENT '是否兼容(1兼容 0不兼容)',
    PRIMARY KEY (machine_id, mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机台模具兼容表';

DROP TABLE IF EXISTS resource_capability;
CREATE TABLE resource_capability (
    resource_id     BIGINT(20)      NOT NULL COMMENT '资源ID',
    op_code         VARCHAR(32) NOT NULL COMMENT '工序编码(SETUP INJECT POST_QC_PUTAWAY)',
    product_id      BIGINT(20)  NOT NULL COMMENT '产品ID',
    is_enabled      INT(1)      DEFAULT 1 COMMENT '是否启用(1启用 0停用)',
    priority_weight INT(11)     DEFAULT 0 COMMENT '优先级权重',
    PRIMARY KEY (resource_id, op_code, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源能力表';

DROP TABLE IF EXISTS calendar;
CREATE TABLE calendar (
    calendar_id     BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '日历ID',
    calendar_name   VARCHAR(100) DEFAULT ''             COMMENT '日历名称',
    workday_pattern VARCHAR(50) DEFAULT NULL            COMMENT '工作日模式',
    shift_start     VARCHAR(8)  DEFAULT NULL            COMMENT '班次开始(HH:mm)',
    shift_end       VARCHAR(8)  DEFAULT NULL            COMMENT '班次结束(HH:mm)',
    breaks          JSON        DEFAULT NULL            COMMENT '休息时间段数组',
    create_time     DATETIME    DEFAULT NULL            COMMENT '创建时间',
    update_time     DATETIME    DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (calendar_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='班次日历表';

DROP TABLE IF EXISTS product;
CREATE TABLE product (
    product_id    BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '产品ID',
    product_name  VARCHAR(100) DEFAULT ''              COMMENT '产品名称',
    image         VARCHAR(255) DEFAULT ''              COMMENT '图片',
    material_code VARCHAR(64)  DEFAULT ''              COMMENT '材料编码',
    color_code    VARCHAR(32)  DEFAULT ''              COMMENT '颜色编码',
    create_time   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (product_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='产品表';

DROP TABLE IF EXISTS product_mold_param;
CREATE TABLE product_mold_param (
    product_id     BIGINT(20)      NOT NULL COMMENT '产品ID',
    mold_id        BIGINT(20)          NOT NULL COMMENT '模具ID',
    cycle_time_sec DECIMAL(10,2)   DEFAULT NULL COMMENT '单模周期(秒)',
    cavity         INT(11)         DEFAULT NULL COMMENT '可用型腔数',
    yield_rate     DECIMAL(5,4)    DEFAULT NULL COMMENT '良品率',
    utilization    DECIMAL(5,4)    DEFAULT NULL COMMENT '稼动率',
    PRIMARY KEY (product_id, mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品模具参数表';

DROP TABLE IF EXISTS product_route;
CREATE TABLE product_route (
    route_id    BIGINT(20)      NOT NULL COMMENT '路线ID',
    product_id  BIGINT(20)  DEFAULT NULL COMMENT '产品ID',
    version     VARCHAR(20) DEFAULT '' COMMENT '版本',
    is_active   INT(1)      DEFAULT 0 COMMENT '是否启用(1启用 0停用)',
    active_flag BIGINT(20)  GENERATED ALWAYS AS (IF(is_active = 1, product_id, NULL)) STORED COMMENT '生成列:is_active=1时等于product_id,用于数据库级保证单产品仅一条活跃路线',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (route_id),
    KEY idx_product_route_product (product_id, is_active, version),
    UNIQUE KEY uk_product_route_active (active_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品工艺路线表';

DROP TABLE IF EXISTS route_operation;
CREATE TABLE route_operation (
    op_id                 BIGINT(20)      NOT NULL COMMENT '工序ID',
    route_id              BIGINT(20)      DEFAULT NULL COMMENT '路线ID',
    op_code               VARCHAR(32) DEFAULT '' COMMENT '工序编码(SETUP INJECT POST_QC_PUTAWAY)',
    sequence              INT(11)     DEFAULT NULL COMMENT '工序顺序',
    eligible_resource_rule VARCHAR(100) DEFAULT '' COMMENT '可用资源规则编码',
    std_time_model        VARCHAR(50) DEFAULT NULL COMMENT '标准工时模型编码',
    queue_policy          VARCHAR(50) DEFAULT NULL COMMENT '排队策略',
    PRIMARY KEY (op_id),
    KEY idx_route_operation_route (route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路线工序表';

DROP TABLE IF EXISTS changeover_rule;
CREATE TABLE changeover_rule (
    rule_id                 BIGINT(20)      NOT NULL COMMENT '规则ID',
    same_mold_time_min      INT(11)     DEFAULT NULL COMMENT '同模具换型时间(分钟)',
    different_mold_time_min INT(11)     DEFAULT NULL COMMENT '不同模具换型时间(分钟)',
    material_change_extra_min INT(11)   DEFAULT NULL COMMENT '换料附加时间(分钟)',
    color_change_extra_min  INT(11)     DEFAULT NULL COMMENT '换色附加时间(分钟)',
    create_time             DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time             DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='换型规则表';

-- ----------------------------
-- 数据版本表（服务自有权属表，Phase 3 新增；为 Phase 4 排程版本化输入快照提供
-- 契约级 snapshotVersion——任一主数据写事务内单调递增，见 MasterDataVersionService）
-- ----------------------------
CREATE TABLE IF NOT EXISTS master_data_data_version (
    scope        VARCHAR(32) NOT NULL COMMENT '版本域(当前仅 MASTER_DATA 单域)',
    data_version BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '单调递增数据版本号',
    updated_at   DATETIME     DEFAULT NULL COMMENT '最后递增时间',
    PRIMARY KEY (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主数据变更版本表(服务权属,非单体基线表)';

SET FOREIGN_KEY_CHECKS = 1;
