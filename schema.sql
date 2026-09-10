-- ----------------------------------------------------------------------------
-- 注塑排程系统 数据库初始化脚本
-- 目标库:MySQL 8.x,字符集 utf8mb4
-- 用途:本地开发一键建库(dev-up.sh 空库时自动导入本文件)
-- 说明:本脚本由实体类/Mapper XML 重建,包含可登录的最小种子数据:
--       管理员账号 admin / admin123(BCrypt)
-- ----------------------------------------------------------------------------

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 系统管理表
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
-- 代码生成表
-- ----------------------------

DROP TABLE IF EXISTS gen_table;
CREATE TABLE gen_table (
    table_id         BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '编号',
    table_name       VARCHAR(200) DEFAULT ''              COMMENT '表名称',
    table_comment    VARCHAR(500) DEFAULT ''              COMMENT '表描述',
    sub_table_name   VARCHAR(64)  DEFAULT NULL            COMMENT '关联子表的表名',
    sub_table_fk_name VARCHAR(64) DEFAULT NULL            COMMENT '子表关联的外键名',
    class_name       VARCHAR(100) DEFAULT ''              COMMENT '实体类名称',
    tpl_category     VARCHAR(200) DEFAULT 'crud'          COMMENT '使用的模板(crud单表操作 tree树表操作 sub主子表操作)',
    tpl_web_type     VARCHAR(30)  DEFAULT ''              COMMENT '前端模板类型(element-ui模板 element-plus模板)',
    package_name     VARCHAR(100) DEFAULT NULL            COMMENT '生成包路径',
    module_name      VARCHAR(30)  DEFAULT NULL            COMMENT '生成模块名',
    business_name    VARCHAR(30)  DEFAULT NULL            COMMENT '生成业务名',
    function_name    VARCHAR(50)  DEFAULT NULL            COMMENT '生成功能名',
    function_author  VARCHAR(50)  DEFAULT NULL            COMMENT '生成功能作者',
    gen_type         CHAR(1)      DEFAULT '0'             COMMENT '生成代码方式(0zip压缩包 1自定义路径)',
    gen_path         VARCHAR(200) DEFAULT '/'             COMMENT '生成路径(不填默认项目路径)',
    options          VARCHAR(1000) DEFAULT NULL           COMMENT '其它生成选项',
    remark           VARCHAR(500) DEFAULT NULL            COMMENT '备注',
    create_time      DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time      DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (table_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='代码生成业务表';

DROP TABLE IF EXISTS gen_table_column;
CREATE TABLE gen_table_column (
    column_id     BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '编号',
    table_id      BIGINT(20)   DEFAULT NULL            COMMENT '归属表编号',
    column_name   VARCHAR(200) DEFAULT ''              COMMENT '列名称',
    column_comment VARCHAR(500) DEFAULT ''             COMMENT '列描述',
    column_type   VARCHAR(100) DEFAULT ''              COMMENT '列类型',
    java_type     VARCHAR(500) DEFAULT ''              COMMENT 'JAVA类型',
    java_field    VARCHAR(200) DEFAULT ''              COMMENT 'JAVA字段名',
    is_pk         CHAR(1)      DEFAULT '0'             COMMENT '是否主键(1是)',
    is_increment  CHAR(1)      DEFAULT '1'             COMMENT '是否自增(1是)',
    is_required   CHAR(1)      DEFAULT '0'             COMMENT '是否必填(1是)',
    is_insert     CHAR(1)      DEFAULT '1'             COMMENT '是否为插入字段(1是)',
    is_edit       CHAR(1)      DEFAULT '1'             COMMENT '是否编辑字段(1是)',
    is_list       CHAR(1)      DEFAULT '1'             COMMENT '是否列表字段(1是)',
    is_query      CHAR(1)      DEFAULT '1'             COMMENT '是否查询字段(1是)',
    query_type    VARCHAR(200) DEFAULT 'EQ'            COMMENT '查询方式(等于、不等于、大于、小于、范围)',
    html_type     VARCHAR(100) DEFAULT ''              COMMENT '显示类型(文本框、文本域、下拉框、复选框、单选框、日期控件)',
    dict_type     VARCHAR(200) DEFAULT ''              COMMENT '字典类型',
    sort          INT(11)      DEFAULT NULL            COMMENT '排序',
    create_time   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (column_id),
    KEY idx_gen_table_column_table_id (table_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='代码生成业务表字段';

-- ----------------------------
-- 主数据表
-- ----------------------------

DROP TABLE IF EXISTS resource;
CREATE TABLE resource (
    resource_id   VARCHAR(64)  NOT NULL                COMMENT '资源ID',
    resource_type VARCHAR(20)  DEFAULT ''              COMMENT '资源类型(MACHINE注塑机 MOLD模具 PERSON人员 WORKSTATION工位)',
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
    machine_id            VARCHAR(64) NOT NULL COMMENT '机台ID(与resource.resource_id对应)',
    tonnage               INT(11)     DEFAULT NULL COMMENT '锁模力',
    default_setup_time_min INT(11)    DEFAULT NULL COMMENT '默认换模基准时间(分钟)',
    PRIMARY KEY (machine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注塑机表';

DROP TABLE IF EXISTS mold;
CREATE TABLE mold (
    mold_id       VARCHAR(64) NOT NULL COMMENT '模具ID(与resource.resource_id对应)',
    mold_code     VARCHAR(64) DEFAULT '' COMMENT '模具业务编号',
    cavity        INT(11)     DEFAULT NULL COMMENT '型腔数',
    mold_status   VARCHAR(20) DEFAULT 'AVAILABLE' COMMENT '模具状态(AVAILABLE可用 IN_USE使用中 REPAIR维修中 MAINTENANCE保养中)',
    next_maint_due DATE       DEFAULT NULL COMMENT '下次保养到期日',
    PRIMARY KEY (mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模具表';

DROP TABLE IF EXISTS machine_mold_compatibility;
CREATE TABLE machine_mold_compatibility (
    machine_id    VARCHAR(64) NOT NULL COMMENT '机台ID',
    mold_id       VARCHAR(64) NOT NULL COMMENT '模具ID',
    is_compatible INT(1)      DEFAULT 1 COMMENT '是否兼容(1兼容 0不兼容)',
    PRIMARY KEY (machine_id, mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机台模具兼容表';

DROP TABLE IF EXISTS resource_capability;
CREATE TABLE resource_capability (
    resource_id     VARCHAR(64) NOT NULL COMMENT '资源ID',
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

DROP TABLE IF EXISTS customer;
CREATE TABLE customer (
    customer_id   BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '客户ID',
    customer_name VARCHAR(100) DEFAULT ''              COMMENT '客户名',
    remark        VARCHAR(500) DEFAULT ''              COMMENT '备注',
    create_time   DATETIME     DEFAULT NULL            COMMENT '创建时间',
    update_time   DATETIME     DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (customer_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='客户表';

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
    mold_id        VARCHAR(64)     NOT NULL COMMENT '模具ID',
    cycle_time_sec DECIMAL(10,2)   DEFAULT NULL COMMENT '单模周期(秒)',
    cavity         INT(11)         DEFAULT NULL COMMENT '可用型腔数',
    yield_rate     DECIMAL(5,4)    DEFAULT NULL COMMENT '良品率',
    utilization    DECIMAL(5,4)    DEFAULT NULL COMMENT '稼动率',
    PRIMARY KEY (product_id, mold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品模具参数表';

DROP TABLE IF EXISTS product_route;
CREATE TABLE product_route (
    route_id    VARCHAR(64) NOT NULL COMMENT '路线ID',
    product_id  BIGINT(20)  DEFAULT NULL COMMENT '产品ID',
    version     VARCHAR(20) DEFAULT '' COMMENT '版本',
    is_active   INT(1)      DEFAULT 0 COMMENT '是否启用(1启用 0停用)',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (route_id),
    KEY idx_product_route_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品工艺路线表';

DROP TABLE IF EXISTS route_operation;
CREATE TABLE route_operation (
    op_id                 VARCHAR(64) NOT NULL COMMENT '工序ID',
    route_id              VARCHAR(64) DEFAULT NULL COMMENT '路线ID',
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
    rule_id                 VARCHAR(64) NOT NULL COMMENT '规则ID',
    same_mold_time_min      INT(11)     DEFAULT NULL COMMENT '同模具换型时间(分钟)',
    different_mold_time_min INT(11)     DEFAULT NULL COMMENT '不同模具换型时间(分钟)',
    material_change_extra_min INT(11)   DEFAULT NULL COMMENT '换料附加时间(分钟)',
    color_change_extra_min  INT(11)     DEFAULT NULL COMMENT '换色附加时间(分钟)',
    create_time             DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time             DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='换型规则表';

-- ----------------------------
-- 需求与订单表
-- ----------------------------

DROP TABLE IF EXISTS customer_order;
CREATE TABLE customer_order (
    order_id    VARCHAR(64) NOT NULL COMMENT '订单ID',
    customer_id BIGINT(20)  DEFAULT NULL COMMENT '客户ID',
    due_date    DATETIME    DEFAULT NULL COMMENT '交期',
    priority    BIGINT(20)  DEFAULT NULL COMMENT '优先级',
    status      VARCHAR(20) DEFAULT 'NEW' COMMENT '订单状态(NEW未确认 CONFIRMED已确认 IN_PRODUCTION生产中 DONE已完成)',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (order_id),
    KEY idx_customer_order_customer (customer_id),
    KEY idx_customer_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户订单表';

DROP TABLE IF EXISTS order_line;
CREATE TABLE order_line (
    order_line_id BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '订单行ID',
    order_id      VARCHAR(64) DEFAULT NULL COMMENT '所属订单ID',
    product_id    BIGINT(20)  DEFAULT NULL COMMENT '产品ID',
    qty           BIGINT(20)  DEFAULT NULL COMMENT '需求数量',
    allocated_qty BIGINT(20)  DEFAULT 0    COMMENT '已拆批数量',
    status        VARCHAR(20) DEFAULT 'NEW' COMMENT '订单行状态(NEW未释放 RELEASE已释放 IN_PRODUCTION生产中 DONE已完成)',
    PRIMARY KEY (order_line_id),
    KEY idx_order_line_order (order_id),
    KEY idx_order_line_product (product_id)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COMMENT='订单行表';

-- ----------------------------
-- 排程表
-- ----------------------------

DROP TABLE IF EXISTS production_batch;
CREATE TABLE production_batch (
    batch_id     VARCHAR(64) NOT NULL COMMENT '批次ID',
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
    task_id             VARCHAR(64) NOT NULL COMMENT '任务ID',
    batch_id            VARCHAR(64) DEFAULT NULL COMMENT '所属批次ID',
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
    pre_task_id  VARCHAR(64) NOT NULL COMMENT '前置任务ID',
    post_task_id VARCHAR(64) NOT NULL COMMENT '后置任务ID',
    PRIMARY KEY (pre_task_id, post_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖表';

DROP TABLE IF EXISTS task_resource_requirement;
CREATE TABLE task_resource_requirement (
    requirement_id              VARCHAR(64) NOT NULL COMMENT '需求ID',
    task_id                     VARCHAR(64) DEFAULT NULL COMMENT '任务ID',
    resource_type               VARCHAR(20) DEFAULT NULL COMMENT '资源类型(MACHINE注塑机 MOLD模具 PERSON人员 WORKSTATION工位)',
    resource_role               VARCHAR(30) DEFAULT NULL COMMENT '资源角色',
    resource_id                 VARCHAR(64) DEFAULT NULL COMMENT '指定资源ID(为空表示按能力匹配)',
    capability_code             VARCHAR(64) DEFAULT NULL COMMENT '能力编码',
    required_count              INT(11)     DEFAULT 1 COMMENT '需求数量',
    is_mandatory                INT(1)      DEFAULT 1 COMMENT '是否硬约束(1是 0否)',
    changeover_source_resource_id VARCHAR(64) DEFAULT NULL COMMENT '换型来源资源ID',
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
    task_id              VARCHAR(64) DEFAULT NULL COMMENT '任务ID',
    machine_id           VARCHAR(64) DEFAULT NULL COMMENT '注塑机ID',
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
    task_id                VARCHAR(64) DEFAULT NULL COMMENT '任务ID',
    resource_id            VARCHAR(64) DEFAULT NULL COMMENT '资源ID',
    resource_type          VARCHAR(20) DEFAULT NULL COMMENT '资源类型(MACHINE MOLD PERSON WORKSTATION)',
    resource_role          VARCHAR(30) DEFAULT NULL COMMENT '资源角色',
    requirement_id         VARCHAR(64) DEFAULT NULL COMMENT '对应资源需求ID',
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
    job_id               VARCHAR(64) NOT NULL COMMENT '任务ID',
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
-- 生产执行表
-- ----------------------------

DROP TABLE IF EXISTS task_event;
CREATE TABLE task_event (
    event_id    VARCHAR(64) NOT NULL COMMENT '事件ID',
    task_id     VARCHAR(64) DEFAULT NULL COMMENT '任务ID',
    event_type  VARCHAR(20) DEFAULT NULL COMMENT '事件类型(START任务开始 PAUSE任务暂停 RESUME任务恢复 FINISH任务完工)',
    event_time  DATETIME    DEFAULT NULL COMMENT '事件时间',
    operator_id VARCHAR(64) DEFAULT NULL COMMENT '操作人ID',
    resource_id VARCHAR(64) DEFAULT NULL COMMENT '发生事件的资源',
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
    event_id        VARCHAR(64) NOT NULL COMMENT '事件ID',
    resource_id     VARCHAR(64) DEFAULT NULL COMMENT '资源ID',
    time            DATETIME    DEFAULT NULL COMMENT '发生时间',
    from_status     VARCHAR(20) DEFAULT NULL COMMENT '原状态',
    to_status       VARCHAR(20) DEFAULT NULL COMMENT '新状态',
    reason_code     VARCHAR(50) DEFAULT NULL COMMENT '原因编码',
    related_task_id VARCHAR(64) DEFAULT NULL COMMENT '关联任务ID',
    create_time     DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time     DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (event_id),
    KEY idx_resource_status_event_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源状态事件表';

-- ----------------------------
-- 种子数据:账号、角色、菜单、字典
-- ----------------------------

-- 初始管理员 admin / admin123(BCrypt)
INSERT INTO sys_user (user_id, user_name, email, phonenumber, sex, avatar, password, status, del_flag, create_time)
VALUES (1, 'admin', 'admin@example.com', '', '0', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', NOW());

INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_time)
VALUES (1, '系统管理员', 'admin', 1, '1', 1, 1, '0', '0', NOW()),
       (2, '普通角色', 'common', 2, '2', 1, 1, '0', '0', NOW());

INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1);

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
