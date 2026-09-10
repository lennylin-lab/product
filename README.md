# 注塑排程系统

一个基于 Spring Boot 3.5 的注塑生产排程管理系统，采用 Maven 多模块架构，覆盖从订单管理、生产排程到生产执行的完整业务链路。

## 项目简介

本系统面向注塑制造场景，围绕“计划 - 派工 - 执行 - 异常 - 完工”组织业务能力，主要包括：

- 订单管理：客户订单录入、查询、导入导出、状态跟踪
- 生产排程：基于交期、优先级与资源约束的排程与任务分配
- 生产执行：任务事件记录、状态流转、过程追溯
- 主数据管理：机台、日历等基础资源维护
- 系统管理：用户、角色、菜单、字典等后台能力
- 认证授权：登录、验证码、JWT、权限校验
- 代码生成：基于表结构生成后端与前端代码

仓库中的 `docs/` 目录还补充了注塑工艺流程、排程方案落地现状、关键配置流程、代码生成规则等说明文档。

`deploy/` 目录补充了 Docker 化 MySQL 和 ELK 日志系统的部署说明。

## 技术栈

### 后端技术

- Java 17
- Spring Boot 3.5.0
- Spring Security
- MyBatis Plus 3.5.9
- Redis
- MySQL 8.x
- Druid 1.2.23
- JWT（jjwt 0.9.1）
- FastJSON2 2.0.53
- Velocity 2.3
- Caffeine 3.2.2
- 阿里云 OSS 3.17.4
- Kaptcha 2.3.3
- Apache POI 4.1.2
- Lombok

### 模块架构（14个模块）

#### 基础支撑模块

- `product-common`：通用工具、常量、异常、注解、缓存与基础组件
- `product-domain`：实体类、DTO、VO
- `product-cache`：Redis 缓存注解与多级缓存工具
- `product-framework`：框架配置、过滤器、拦截器、异常处理、线程池

#### 核心业务模块

- `product-core`：控制器基类、JWT/字典/权限工具等公共能力
- `product-system-api`：系统服务接口抽象
- `product-system`：用户、角色、菜单、字典等系统管理
- `product-auth`：认证授权、验证码、登录、用户信息
- `product-become`：代码生成器、模板、元数据管理
- `product-demand`：客户、产品、订单管理
- `product-pps`：生产批次、任务分配、工序排程
- `product-master`：机台、日历等主数据管理
- `product-execute`：任务事件、生产执行追踪

#### 启动模块

- `product-server`：应用启动模块，聚合所有业务模块并提供运行入口

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+

### 安装步骤

1. 克隆项目

```bash
git clone <repository-url>
cd product
```

2. 创建数据库

```sql
CREATE DATABASE product CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

3. 修改配置文件

主要修改 `product-server/src/main/resources/application.yml` 和 `product-server/src/main/resources/application-druid.yml`。

4. 编译打包

```bash
mvn clean compile
mvn clean package
mvn clean package -DskipTests
mvn clean install
```

5. 准备本地 `.env`

先复制一份开发环境模板：

```bash
cp .env.example .env
```

根目录 `.env` 是开发环境的统一入口：

- `docker compose` 读取它启动 MySQL、Redis 和 ELK
- `./scripts/dev-run.sh` 会自动加载它并导出给 Spring Boot
- `deploy/mysql/.env`、`deploy/redis/.env`、`deploy/elk/.env` 保留为单独启动某个依赖时的独立入口

注意：如果 `.env` 里的值包含空格，例如 `ES_JAVA_OPTS`、`LS_JAVA_OPTS`，要写成带引号的形式，例如 `ES_JAVA_OPTS=\"-Xms1g -Xmx1g\"`。因为这份 `.env` 既会被 `docker compose` 读取，也会被 shell `source`。

6. 启动依赖服务

```bash
./scripts/dev-up.sh
```

该脚本会通过根目录 `compose.dev.yml` 启动本地开发依赖：

- MySQL：`${MYSQL_PORT}`，默认 `33066`
- Redis：`${REDIS_PORT}`，默认 `6379`
- Elasticsearch：`${ELASTICSEARCH_PORT}`，默认 `9200`
- Kibana：`${KIBANA_PORT}`，默认 `5601`
- Logstash API：`${LOGSTASH_API_PORT}`，默认 `9600`

启动完成后，脚本会自动检查 MySQL 中 `${MYSQL_DATABASE}` 是否为空库：

- 启动前先将根目录 [schema.sql](/home/lenny/Projects/pps/product/schema.sql:1) 同步到 `deploy/mysql/init/010-schema.sql`
- 空库时自动导入根目录 [schema.sql](/home/lenny/Projects/pps/product/schema.sql:1)
- 已有业务表时跳过导入，避免覆盖现有开发数据

空库初始化后，系统自带一个管理员账号：

- 用户名 `admin`，密码 `admin123`，首次登录后请立即修改
- 同时写入系统管理菜单、代码生成菜单、业务目录菜单与基础字典（详见 `schema.sql` 的种子数据部分）

如果你不走一键开发脚本，而是想单独启动某个依赖，也可以直接使用模块目录下的配置：

```bash
docker compose -f deploy/mysql/docker-compose.yml up -d
docker compose -f deploy/redis/docker-compose.yml up -d
docker compose -f deploy/elk/docker-compose.yml up -d
```

这两条命令会分别读取：

- [deploy/mysql/.env](/home/lenny/Projects/pps/product/deploy/mysql/.env:1)
- [deploy/redis/.env](/home/lenny/Projects/pps/product/deploy/redis/.env:1)
- [deploy/elk/.env](/home/lenny/Projects/pps/product/deploy/elk/.env:1)

7. 启动服务

```bash
./scripts/dev-run.sh
```

本地开发 profile 为 `local`，会自动包含 `druid` 数据源配置，并优先读取根目录 `.env` 中的值。

如果你启用了 ELK，应用日志会写到 `${PRODUCT_LOG_PATH}`，默认是 `./logs`，Filebeat 会从同一路径采集 JSON 日志。

8. 访问应用

```text
http://localhost:8081
```

本地日志与观测入口：

- Kibana：`http://localhost:5601`
- Elasticsearch：`http://localhost:9200`

## 开发指南

### 分层架构

项目采用经典分层结构：

```text
Controller 层 → Service 层 → Mapper 层 → Entity 层
      ↓             ↓            ↓           ↓
   HTTP 请求      业务逻辑      数据访问      数据模型
```

#### 各层职责

- Controller 层：处理 HTTP 请求、参数校验、返回结果
- Service 层：业务逻辑处理、事务控制
- Mapper 层：数据库访问、SQL 执行
- Entity 层：数据模型定义
- VO 层：视图对象，用于 API 返回和关联查询

### 核心注解说明

#### 通用注解

- `@Anonymous`：匿名访问接口
- `@RepeatSubmit`：防重复提交
- `@Xss`：XSS 防护
- `@Excel`：Excel 导入导出
- `@BizIdPrefix`：业务 ID 前缀

#### 缓存注解

- `@MyRedisCache`：自定义 Redis 缓存注解
- `@IpGuard`：IP 防护注解

### 开发新功能模块

#### 1. 创建模块结构

```text
product-xxx/
├── pom.xml
└── src/main/
    ├── java/com/product/xxx/
    │   ├── controller/
    │   ├── service/
    │   ├── service/impl/
    │   └── mapper/
    └── resources/
        └── mapper/
```

#### 2. 定义实体类

实体类统一放在 `product-domain` 模块的 `entity` 包。

#### 3. 创建 VO 对象（如需关联查询）

#### 4. 创建 Mapper 接口

#### 5. 编写 Mapper XML（如需自定义 SQL）

#### 6. 创建 Service 接口和实现

#### 7. 创建 Controller

### 代码生成器使用

#### 配置代码生成器

编辑 `product-become/src/main/resources/generator.yml`。

#### 生成内容

- Java 代码：Entity、Mapper、Mapper.xml、Service、ServiceImpl、Controller
- Vue3 组件：列表页、详情页
- SQL 脚本：建表语句

#### 模板位置

- Java 模板：`product-become/src/main/resources/vm/java/`
- Vue3 模板：`product-become/src/main/resources/vm/vue/v3/`
- XML 模板：`product-become/src/main/resources/vm/xml/`

### 缓存策略

项目采用多级缓存架构：

1. 本地缓存（Caffeine）：速度快，适合热点数据
2. 分布式缓存（Redis）：容量大，适合共享数据
3. 复杂场景可使用 `MultiCacheUtil` 或 `RedisCache`
4. 热点 Key 场景可开启 `@MyRedisCache(hotKey = true)`，通过 Redis 分布式锁避免击穿

### 安全特性

- XSS 防护：`XssFilter` + `@Xss`
- 防重复提交：Redis 分布式锁 + `@RepeatSubmit`
- 认证授权：Spring Security + JWT
- 登录失败锁定：支持重试次数与锁定时间配置
- 请求链路日志：`TraceMdcFilter` 自动注入 `traceId`、`requestId`、`clientIp` 等 MDC 字段

### Excel 导入导出

```java
List<Entity> list = entityService.selectList(entity);
ExcelUtil<Entity> util = new ExcelUtil<>(Entity.class);
util.exportExcel(response, list, "数据文件名");
```

### 分页查询实现

```java
Page<EntityVO> page = PageUtils.buildPage();
return getDataTable(entityService.selectPage(page, entity));
```

### 排程任务

- 同步排程：`POST /pps/assignment/schedule`
- 全量同步排程：`POST /pps/assignment/scheduleAll`
- 异步全量排程：`POST /pps/assignment/scheduleAllAsync`
- 排程任务查询：`GET /pps/assignment/scheduleJob/{jobId}`
- 排程任务列表：`GET /pps/assignment/scheduleJob/list`
- 超时兜底扫描：`POST /pps/assignment/scheduleJob/sweepTimeout`
- 排程任务状态：`PENDING` → `RUNNING` → `SUCCESS` / `FAILED`
- 进度字段：`totalTaskCount`、`processedTaskCount`、`batchCount`、`progressPercent`

## 常用命令

### Maven 命令

```bash
mvn clean compile
mvn clean package
mvn clean package -DskipTests
mvn clean install
mvn spring-boot:run -pl product-server
cp .env.example .env
./scripts/dev-up.sh
./scripts/dev-run.sh
mvn dependency:tree
mvn test
mvn test -Dtest=ProductServerApplicationTests
```

### Git 命令

```bash
git status
git branch -a
git log --oneline -10
```

## API接口

### 认证相关

- `POST /login`：用户登录
- `POST /logout`：用户登出
- `GET /getInfo`：获取当前用户信息
- `GET /getRouters`：获取路由信息
- `GET /captchaImage`：获取验证码

### 系统管理

- `GET /system/user/list`：用户列表
- `POST /system/user`：新增用户
- `PUT /system/user`：修改用户
- `DELETE /system/user/{userIds}`：删除用户
- `PUT /system/user/resetPwd`：重置密码
- `PUT /system/user/changeStatus`：修改状态
- `GET /system/menu/list`：菜单列表
- `GET /system/menu/treeselect`：菜单树
- `GET /system/dict/type/list`：字典类型列表
- `GET /system/dict/data/list`：字典数据列表

### 订单管理

- `GET /demand/customer/list`：客户列表
- `POST /demand/customer`：新增客户
- `PUT /demand/customer`：修改客户
- `DELETE /demand/customer/{customerIds}`：删除客户
- `GET /demand/product/list`：产品列表
- `POST /demand/product`：新增产品
- `GET /demand/order/list`：订单列表
- `POST /demand/order/export`：导出订单
- `POST /demand/order/importData`：导入订单
- `POST /demand/order/importTemplate`：下载模板
- `PUT /demand/order/check/{orderId}`：订单确认
- `PUT /demand/order/cancelCheck/{orderId}`：取消确认
- `GET /demand/orderLine/list`：订单行列表
- `PUT /demand/orderLine/release/{orderLineId}`：释放订单行
- `PUT /demand/orderLine/cancelRelease/{orderLineId}`：取消释放

### 主数据管理

- `GET /master/resource/machine/list`：机台列表
- `POST /master/resource/machine`：新增机台
- `PUT /master/resource/machine`：修改机台
- `DELETE /master/resource/machine/{machineIds}`：删除机台
- `PUT /master/resource/machine/down/{machineId}`：机台停机
- `PUT /master/resource/machine/maintenance/{machineId}`：机台保养
- `PUT /master/resource/machine/restore/{machineId}`：机台恢复
- `GET /master/calendar/list`：日历列表
- `POST /master/calendar`：新增日历

### 生产排程

- `GET /pps/batch/list`：生产批次列表
- `POST /pps/batch`：创建生产批次
- `PUT /pps/batch/release/{batchId}`：释放批次
- `PUT /pps/batch/cancelRelease/{batchId}`：取消释放
- `POST /pps/batch/generateTask`：生成任务
- `PUT /pps/batch/retryGenerateTask/{batchId}`：重试生成任务
- `GET /pps/task/list`：工序任务列表
- `PUT /pps/task/cancel/{taskId}`：取消任务
- `PUT /pps/task/restore/{taskId}`：恢复任务
- `PUT /pps/task/revokeSchedule/{taskId}`：撤销排程
- `GET /pps/assignment/list`：任务分配列表
- `POST /pps/assignment/schedule`：排程
- `POST /pps/assignment/scheduleAll`：批量排程
- `POST /pps/assignment/scheduleAllAsync`：异步批量排程
- `GET /pps/assignment/scheduleJob/{jobId}`：查询排程任务进度
- `GET /pps/assignment/scheduleJob/list`：分页查询排程任务
- `POST /pps/assignment/scheduleJob/sweepTimeout`：手动触发超时兜底扫描
- `GET /pps/product-route/list`：工艺路线列表
- `GET /pps/product-route/product/{productId}`：产品的路线列表
- `GET /pps/product-route/product/{productId}/active`：产品当前生效路线
- `POST /pps/product-route`：创建工艺路线
- `PUT /pps/product-route`：修改工艺路线
- `PUT /pps/product-route/{routeId}/activate`：激活路线版本
- `DELETE /pps/product-route/{routeId}`：删除工艺路线

### 生产执行

- `GET /execute/event/list`：任务事件列表
- `POST /execute/event`：记录任务事件
- `POST /execute/event/start/{taskId}`：开始任务
- `POST /execute/event/pause/{taskId}`：暂停任务
- `POST /execute/event/resume/{taskId}`：恢复任务
- `POST /execute/event/complete/{taskId}`：完工

### 代码生成

- `GET /tool/gen/list`：代码生成表列表
- `POST /tool/gen/importTable`：导入表
- `GET /tool/gen/preview/{tableId}`：预览代码
- `GET /tool/gen/download/{tableName}`：下载代码
- `GET /tool/gen/genCode/{tableName}`：生成代码

### 通用功能

- `POST /common/upload`：文件上传

## 配置说明

### application.yml 主配置

- 服务端口：8081
- 文件上传路径
- 验证码类型
- Redis 配置
- Token 配置
- MyBatis 配置
- XSS 防护配置
- 登录失败锁定配置
- OSS 配置
- 排程配置：`product.pps.schedule.batch-size`、`timeout-minutes`、`timeout-scan-delay-ms`

### application-druid.yml 数据源配置

- 数据源类型
- JDBC 驱动
- 数据库连接地址
- 账号密码
- Druid 连接池参数
- 连接参数支持通过环境变量覆盖，便于 Docker / 生产环境部署

### mybatis-config.xml

- MyBatis 行为设置
- 日志实现
- 缓存与执行器配置

### logback-spring.xml

- 控制台日志输出
- 应用日志、错误日志、审计日志分流
- JSON 日志格式
- 日志滚动策略
- 日志字段包含 `traceId`、`requestId`、`userId`、`username`、`clientIp`、`httpMethod`、`requestUri`

### generator.yml 代码生成配置

- 作者名
- 包路径
- 表前缀
- 是否允许覆盖

### 容器化部署

- MySQL 容器部署说明：`deploy/mysql/README.md`
- ELK 日志系统部署说明：`deploy/elk/README.md`
- MySQL 环境变量示例：`deploy/mysql/.env`

## 项目进度

### 已完成模块

- 基础框架搭建
- 系统管理功能
- 认证授权功能
- 代码生成器
- 产品与订单模块
- 主数据（机台、日历）管理

### 已实现的排程相关能力

- 生产批次管理与释放控制
- 工序任务生成与生命周期管理（取消、恢复、撤销排程）
- 基础排程与任务分配，支持四种策略（`EARLIEST_START`、`EARLIEST_FINISH`、`DUE_DATE_PRIORITY`、`LOWEST_COST`）
- 多资源排程：机台与模具硬约束（资源兼容性过滤）、任务依赖约束
- 换型时间计算（同/异模具、换料、换色规则）
- 工艺路线闭环：规则注册表、路线 CRUD、产品绑定、`queue_policy`/标准工时模型扩展
- 任务事件追踪（开始/暂停/恢复/完工）并联动批次、订单行、订单状态
- 异步全量排程任务与进度查询、排程超时兜底扫描

### 待开发功能

- 人员、工位、夹具等协同资源从模型层进入排程分配与占用计算
- 异常事件、资源故障等执行侧事件建模与重排触发
- 成本模型细化（能耗、换模次数、跨班次损耗等综合因子）
- 报表统计分析
- 系统级与集成级测试（跨模块状态联动、并发排程场景）

## 开发规范

### 命名规范

- 包名：全小写，使用点分隔
- 类名：大驼峰命名法
- 方法名和变量名：小驼峰命名法
- 常量：全大写，下划线分隔

### API 设计规范

- 遵循 RESTful 设计原则
- 统一使用 `AjaxResult` 返回
- 分页接口统一返回 `TableDataInfo`

### 数据库规范

- 表名：小写，下划线分隔
- 字段名：小写，下划线分隔
- 必须字段：`create_time`、`update_time`（由 `BaseEntity` 统一维护）
- 逻辑删除：`del_flag`（仅系统表使用）

### 事务管理

- Service 层方法使用 `@Transactional`
- 查询方法可使用只读事务
- 避免在事务中调用外部 API

## 常见问题

### 分页查询不生效

确保：

1. `MybatisConfig` 已配置分页拦截器
2. 使用 `Page<T>` 对象作为参数
3. 不要在 Mapper XML 中手写 `limit`

### 缓存注解不生效

确保：

1. 方法是 `public`
2. 类是 Spring 管理的 Bean
3. SpEL 表达式语法正确

### Mapper 接口找不到

确保：

1. `@MapperScan` 配置正确
2. Mapper 接口加了 `@Mapper`
3. Mapper XML 路径配置正确

### 启动报数据源错误

确保：

1. 数据源配置正确
2. DruidConfig 已加载
3. MySQL 连接依赖已引入

## 文档目录

- `docs/README.md`：项目文档总索引
- `CLAUDE.md`：Claude Code 项目指南
- `docs/`：项目文档目录
  - `docs/生产计划与排程计划系统需求说明书.md`
  - `docs/关键配置流程.md`
  - `docs/注塑件加工流程.md`
  - `docs/自动排程伪代码.md`
  - `docs/gen-utils-guide.md`
  - `docs/velocity-utils-guide.md`
  - `docs/pagination-plugin-issue.md`
  - `docs/gen-table-sql-fix.md`
- `docs/生产排程解决方案落地现状.md`
- `docs/TODO.md`
- `deploy/README.md`：部署文档总索引
- `deploy/mysql/README.md`：MySQL Docker 迁移说明
- `deploy/elk/README.md`：ELK 日志系统部署说明

## 贡献指南

欢迎提交 Issue 和 Pull Request。

## 许可证

本项目采用私有许可证。

## 联系方式

如有问题，请联系开发团队。

---

最后更新时间：2026-09-10
当前版本：0.0.1-SNAPSHOT
