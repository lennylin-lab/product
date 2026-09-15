-- ----------------------------------------------------------------------------
-- product-identity 数据库初始化脚本（Phase 2，ADR-0005 database per service）
-- 目标库:identity_db（共享 MySQL 8.4 实例上的独立 database）
-- 账号:identity_svc（最小权限:仅 identity_db 的 SELECT/INSERT/UPDATE/DELETE）
-- 来源:根 schema.sql 的 sys_* 7 张表 + 种子数据原样提取（表结构与种子 ID/AUTO 起点未改动）
-- 幂等性:整体可重复执行 —— 重复执行将 identity_db 重置为与单体一致的已知初始状态
--         （CREATE DATABASE/USER IF NOT EXISTS + 表级 DROP/CREATE + 种子重灌，与根 schema.sql 语义一致）
-- 回滚点:执行本脚本即恢复"身份库初始备份"的等价状态（implement.md Phase 2 Rollback point）
-- 注意:本脚本不修改根 schema.sql；密码请通过环境变量注入生产环境（此处默认值仅限本地开发）
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS identity_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE identity_db;

-- 独立最小权限账号（ADR-0005 §4:服务账号只能访问自己的 database）
-- 开发默认密码 identity-dev-pwd（与 .env.example 的 IDENTITY_DB_PASSWORD 默认值一致）
-- 生产环境执行前先替换下行两条语句中的密码。
CREATE USER IF NOT EXISTS 'identity_svc'@'%' IDENTIFIED BY 'identity-dev-pwd';
ALTER USER 'identity_svc'@'%' IDENTIFIED BY 'identity-dev-pwd';
GRANT SELECT, INSERT, UPDATE, DELETE ON identity_db.* TO 'identity_svc'@'%';
FLUSH PRIVILEGES;

-- ----------------------------
-- 系统管理表（与根 schema.sql 逐字段一致）
-- ----------------------------

DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    user_id       BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    user_name     VARCHAR(30)  NOT NULL                COMMENT '登录名称',
    email         VARCHAR(50)  DEFAULT ''              COMMENT '用户邮箱',
    phonenumber   VARCHAR(11)  DEFAULT ''              COMMENT '手机号码',
    sex           CHAR(1)      DEFAULT '0'             COMMENT '用户性别(0男 1女 2未知)',
    avatar        VARCHAR(100) DEFAULT ''              COMMENT '头像路径',
    password      VARCHAR(100) DEFAULT ''              COMMENT '密码(BCrypt)',
    status        CHAR(1)      DEFAULT '0'             COMMENT '账号状态(0正常 1停用)',
    del_flag      CHAR(1)      DEFAULT '0'             COMMENT '删除标志(0存在 2删除)',
    login_ip      VARCHAR(128) DEFAULT ''              COMMENT '最后登录IP',
    login_date    DATETIME     DEFAULT NULL            COMMENT '最后登录时间',
    create_time   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (user_id),
    KEY idx_sys_user_name (user_name)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    role_id             BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    role_name           VARCHAR(30) NOT NULL                COMMENT '角色名称',
    role_key            VARCHAR(100) DEFAULT ''             COMMENT '角色权限字符串',
    role_sort           INT(4)      NOT NULL DEFAULT 0      COMMENT '显示顺序',
    data_scope          CHAR(1)     DEFAULT '1'             COMMENT '数据范围(1全部 2自定义 3本部门 4本部门及以下 5仅本人)',
    menu_check_strictly TINYINT(1)  DEFAULT 1               COMMENT '菜单树选择项是否关联显示',
    dept_check_strictly TINYINT(1)  DEFAULT 1               COMMENT '部门树选择项是否关联显示',
    status              CHAR(1)     DEFAULT '0'             COMMENT '角色状态(0正常 1停用)',
    del_flag            CHAR(1)     DEFAULT '0'             COMMENT '删除标志(0存在 2删除)',
    create_time         DATETIME    DEFAULT NULL            COMMENT '创建时间',
    update_time         DATETIME    DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (role_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='角色信息表';

DROP TABLE IF EXISTS sys_menu;
CREATE TABLE sys_menu (
    menu_id     BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    menu_name   VARCHAR(50) NOT NULL                COMMENT '菜单名称',
    parent_id   BIGINT(20)  DEFAULT 0               COMMENT '父菜单ID',
    order_num   INT(4)      DEFAULT 0               COMMENT '显示顺序',
    path        VARCHAR(200) DEFAULT ''             COMMENT '路由地址',
    component   VARCHAR(255) DEFAULT NULL           COMMENT '组件路径',
    query       VARCHAR(255) DEFAULT NULL           COMMENT '路由参数',
    route_name  VARCHAR(50)  DEFAULT ''             COMMENT '路由名称',
    is_frame    INT(1)      DEFAULT 1               COMMENT '是否为外链(0是 1否)',
    is_cache    INT(1)      DEFAULT 0               COMMENT '是否缓存(0缓存 1不缓存)',
    menu_type   CHAR(1)     DEFAULT ''              COMMENT '菜单类型(M目录 C菜单 F按钮)',
    visible     CHAR(1)     DEFAULT '0'             COMMENT '显示状态(0显示 1隐藏)',
    status      CHAR(1)     DEFAULT '0'             COMMENT '菜单状态(0正常 1停用)',
    perms       VARCHAR(100) DEFAULT NULL           COMMENT '权限标识',
    icon        VARCHAR(100) DEFAULT '#'            COMMENT '菜单图标',
    create_time DATETIME    DEFAULT NULL            COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (menu_id)
) ENGINE=InnoDB AUTO_INCREMENT=2000 DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';

DROP TABLE IF EXISTS sys_role_menu;
CREATE TABLE sys_role_menu (
    role_id BIGINT(20) NOT NULL COMMENT '角色ID',
    menu_id BIGINT(20) NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色和菜单关联表';

DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
    user_id BIGINT(20) NOT NULL COMMENT '用户ID',
    role_id BIGINT(20) NOT NULL DEFAULT 0      COMMENT '角色ID',
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户和角色关联表';

DROP TABLE IF EXISTS sys_dict_type;
CREATE TABLE sys_dict_type (
    dict_id     BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '字典主键',
    dict_name   VARCHAR(100) DEFAULT ''              COMMENT '字典名称',
    dict_type   VARCHAR(100) DEFAULT ''              COMMENT '字典类型',
    status      CHAR(1)      DEFAULT '0'             COMMENT '状态(0正常 1停用)',
    remark      VARCHAR(500) DEFAULT NULL            COMMENT '备注',
    create_time DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (dict_id),
    UNIQUE KEY dict_type (dict_type)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='字典类型表';

DROP TABLE IF EXISTS sys_dict_data;
CREATE TABLE sys_dict_data (
    dict_code    BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '字典编码',
    dict_sort    BIGINT(20)   DEFAULT 0               COMMENT '字典排序',
    dict_label   VARCHAR(100) DEFAULT ''              COMMENT '字典标签',
    dict_value   VARCHAR(100) DEFAULT ''              COMMENT '字典键值',
    dict_type    VARCHAR(100) DEFAULT ''              COMMENT '字典类型',
    css_class    VARCHAR(100) DEFAULT NULL            COMMENT '样式属性',
    list_class   VARCHAR(100) DEFAULT NULL            COMMENT '表格回显样式',
    is_default   CHAR(1)      DEFAULT 'N'             COMMENT '是否默认(Y是 N否)',
    is_readonly  CHAR(1)      DEFAULT '1'             COMMENT '是否只读(0只读 1读写)',
    status       CHAR(1)      DEFAULT '0'             COMMENT '状态(0正常 1停用)',
    create_time  DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time  DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (dict_code),
    KEY idx_sys_dict_type (dict_type)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='字典数据表';

-- ----------------------------
-- 种子数据:账号、角色、菜单、字典（与根 schema.sql 一致）
-- ----------------------------

-- 初始管理员 admin / admin123(BCrypt)
INSERT INTO sys_user (user_id, user_name, email, phonenumber, sex, avatar, password, status, del_flag, create_time)
VALUES (1, 'admin', 'admin@example.com', '', '0', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', NOW());

-- Phase 4 新增（内部服务身份账号，非单体基线种子）：planning_svc / planning-svc-dev-pwd(BCrypt)。
-- 供 product-planning 异步排程经 POST /internal/identity/service-token 换取 RS256 token
-- （ADR-0003 服务身份最小实现；网关对 /internal/** 显式 404）。该账号挂普通角色(common)；
-- 角色-菜单种子引用的菜单 ID 在本库菜单种子中不存在，故 token 的 permissions 解析为空集
-- （与 Phase 2 实测的 common 角色用户一致：业务域端点仅要求登录，无权限串）。
INSERT INTO sys_user (user_id, user_name, email, phonenumber, sex, avatar, password, status, del_flag, create_time)
VALUES (2, 'planning_svc', 'planning-svc@internal', '', '0', '', '$2a$10$735KiuLjegsM30OBDnJfkOWYDV/DyfxlCP1.P0rbAASG8ViL17nAS', '0', '0', NOW());

INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_time)
VALUES (1, '系统管理员', 'admin', 1, '1', 1, 1, '0', '0', NOW()),
       (2, '普通角色', 'common', 2, '2', 1, 1, '0', '0', NOW());

INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1),
       (2, 2);

-- 菜单:系统管理
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_time) VALUES
(1, '系统管理', 0, 1, 'system', NULL, '', '', 1, 0, 'M', '0', '0', '', 'system', NOW()),
(100, '用户管理', 1, 1, 'user', 'system/user/index', '', '', 1, 0, 'C', '0', '0', 'system:user:list', 'user', NOW()),
(101, '菜单管理', 1, 3, 'menu', 'system/menu/index', '', '', 1, 0, 'C', '0', '0', 'system:menu:list', 'tree-table', NOW()),
(105, '字典管理', 1, 6, 'dict', 'system/dict/index', '', '', 1, 0, 'C', '0', '0', 'system:dict:list', 'dict', NOW()),
(1000, '用户查询', 100, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:query', '#', NOW()),
(1001, '用户新增', 100, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:add', '#', NOW()),
(1002, '用户修改', 100, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:edit', '#', NOW()),
(1003, '用户删除', 100, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:remove', '#', NOW()),
(1004, '用户导入', 100, 5, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:import', '#', NOW()),
(1005, '用户导出', 100, 6, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:export', '#', NOW()),
(1006, '重置密码', 100, 7, '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:resetPwd', '#', NOW()),
(1012, '菜单查询', 101, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:query', '#', NOW()),
(1013, '菜单新增', 101, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:add', '#', NOW()),
(1014, '菜单修改', 101, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:edit', '#', NOW()),
(1015, '菜单删除', 101, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:remove', '#', NOW()),
(1055, '字典查询', 105, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:query', '#', NOW()),
(1056, '字典新增', 105, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:add', '#', NOW()),
(1057, '字典修改', 105, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:edit', '#', NOW()),
(1058, '字典删除', 105, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:remove', '#', NOW()),
(1059, '字典导出', 105, 5, '', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:export', '#', NOW());

-- 菜单:系统工具
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_time) VALUES
(5, '系统工具', 0, 5, 'tool', NULL, '', '', 1, 0, 'M', '0', '0', '', 'tool', NOW()),
(115, '代码生成', 5, 1, 'gen', 'tool/gen/index', '', '', 1, 0, 'C', '0', '0', 'tool:gen:list', 'code', NOW()),
(1061, '生成查询', 115, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:query', '#', NOW()),
(1062, '生成代码', 115, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:code', '#', NOW()),
(1063, '生成导入', 115, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:import', '#', NOW()),
(1064, '生成修改', 115, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:edit', '#', NOW()),
(1065, '生成删除', 115, 5, '', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:remove', '#', NOW()),
(1066, '生成预览', 115, 6, '', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:preview', '#', NOW());

-- 菜单:业务目录(按模块组织,组件路径可按前端实际调整)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_time) VALUES
(2, '订单管理', 0, 2, 'demand', NULL, '', '', 1, 0, 'M', '0', '0', '', 'shopping', NOW()),
(201, '客户管理', 2, 1, 'customer', 'demand/customer/index', '', '', 1, 0, 'C', '0', '0', '', 'peoples', NOW()),
(202, '产品管理', 2, 2, 'product', 'demand/product/index', '', '', 1, 0, 'C', '0', '0', '', 'component', NOW()),
(203, '客户订单', 2, 3, 'order', 'demand/order/index', '', '', 1, 0, 'C', '0', '0', '', 'list', NOW()),
(3, '生产排程', 0, 3, 'pps', NULL, '', '', 1, 0, 'M', '0', '0', '', 'date', NOW()),
(211, '生产批次', 3, 1, 'batch', 'pps/batch/index', '', '', 1, 0, 'C', '0', '0', '', 'form', NOW()),
(212, '任务分配', 3, 2, 'assignment', 'pps/assignment/index', '', '', 1, 0, 'C', '0', '0', '', 'tree', NOW()),
(213, '排程任务', 3, 3, 'scheduleJob', 'pps/scheduleJob/index', '', '', 1, 0, 'C', '0', '0', '', 'monitor', NOW()),
(4, '生产执行', 0, 4, 'execute', NULL, '', '', 1, 0, 'M', '0', '0', '', 'job', NOW()),
(221, '任务事件', 4, 1, 'event', 'execute/event/index', '', '', 1, 0, 'C', '0', '0', '', 'log', NOW()),
(6, '主数据', 0, 6, 'master', NULL, '', '', 1, 0, 'M', '0', '0', '', 'build', NOW()),
(231, '机台管理', 6, 1, 'machine', 'master/machine/index', '', '', 1, 0, 'C', '0', '0', '', 'server', NOW()),
(232, '模具管理', 6, 2, 'mold', 'master/mold/index', '', '', 1, 0, 'C', '0', '0', '', 'skill', NOW()),
(233, '资源管理', 6, 3, 'resource', 'master/resource/index', '', '', 1, 0, 'C', '0', '0', '', 'dict', NOW()),
(234, '日历管理', 6, 4, 'calendar', 'master/calendar/index', '', '', 1, 0, 'C', '0', '0', '', 'time', NOW()),
(235, '工艺路线', 6, 5, 'route', 'master/route/index', '', '', 1, 0, 'C', '0', '0', '', 'cascader', NOW());

-- 普通角色授予业务菜单
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id IN (2, 3, 4, 6,
    201, 202, 203, 211, 212, 213, 221, 231, 232, 233, 234, 235);

-- 字典
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, remark, create_time) VALUES
(1, '用户性别', 'sys_user_sex', '0', '用户性别列表', NOW()),
(2, '系统开关', 'sys_normal_disable', '0', '系统开关列表', NOW());

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, is_readonly, status, create_time) VALUES
(1, 1, '男', '0', 'sys_user_sex', '', '', 'Y', '1', '0', NOW()),
(2, 2, '女', '1', 'sys_user_sex', '', '', 'N', '1', '0', NOW()),
(3, 3, '未知', '2', 'sys_user_sex', '', '', 'N', '1', '0', NOW()),
(4, 1, '正常', '0', 'sys_normal_disable', '', 'primary', 'Y', '1', '0', NOW()),
(5, 2, '停用', '1', 'sys_normal_disable', '', 'danger', 'N', '1', '0', NOW());

SET FOREIGN_KEY_CHECKS = 1;
