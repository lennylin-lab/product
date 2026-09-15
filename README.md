# 注塑排程系统

一个基于 Spring Boot 3.5 与 Spring Cloud Alibaba 的注塑生产排程管理系统，覆盖从订单管理、生产排程到生产执行的完整业务链路。2026-09 起项目已由单体多模块整体迁移至微服务体系（网关统一入口 + 五个业务服务），单体模块代码保留作迁移对照。

## 项目简介

本系统面向注塑制造场景，围绕“计划 - 派工 - 执行 - 异常 - 完工”组织业务能力，主要包括：

- 订单管理：客户订单录入、查询、导入导出、状态跟踪
- 生产排程：基于交期、优先级与资源约束的排程与任务分配
- 生产执行：任务事件记录、状态流转、过程追溯
- 主数据管理：机台、模具、日历、工艺路线等基础资源维护
- 系统管理：用户、角色、菜单、字典等后台能力
- 认证授权：登录、验证码、JWT（RS256 + JWKS）、权限校验
- 跨服务事件一致性：事务性 Outbox、幂等消费、死信审计与重放
- 代码生成：基于表结构生成后端与前端代码（单体能力，未随微服务迁移）

仓库中的 `docs/` 目录还补充了注塑工艺流程、关键配置流程、微服务运维手册、数据补偿手册等说明文档。

`deploy/` 目录补充了 Docker 化 MySQL、Redis、ELK 日志系统与告警基线的部署说明。

## 技术栈

### 后端技术

- Java 17
- Spring Boot 3.5（单体 3.5.0；微服务基线 3.5.16，见 ADR-0001）
- Spring Security
- MyBatis Plus 3.5.9
- Redis
- MySQL 8.x
- Druid 1.2.23（单体）
- JWT（单体 jjwt 0.9.1；微服务 jjwt 0.12.6，RS256 + JWKS）
- FastJSON2 2.0.53
- Velocity 2.3
- Caffeine 3.2.2
- 阿里云 OSS 3.17.4
- Kaptcha 2.3.3
- Apache POI 4.1.2
- Lombok

### 微服务技术（2026-09 迁移后，现役）

- Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0
- Spring Cloud Gateway：唯一入口、路由收敛、JWT 前置校验
- Nacos 3.0.x：服务发现 + 配置中心（namespace / group / Data ID 三级隔离）
- Sentinel 1.8.9：网关限流，规则 Nacos 持久化
- RabbitMQ 3.13：跨服务事件（事务性 Outbox + publisher confirm、幂等消费、有界重试 + DLX 死信审计）
- micrometer-tracing + OpenTelemetry OTLP：链路追踪（Jaeger）；Prometheus 指标；ELK JSON 日志

## 模块架构

### 微服务体系（`product-services/`，现役）

> 版本基线与服务边界见 `product-services/adr/`（ADR-0001~0005）；网关路由、认证基线、事件一致性拓扑详见 `product-services/README.md`。

| 模块 | 服务名（Nacos 注册名） | 端口 | 职责 |
| --- | --- | --- | --- |
| `product-gateway` | `product-gateway` | 8080 | 唯一入口：路由、CORS、限流、JWT 前置校验、trace 注入 |
| `product-identity` | `product-identity` | 8101 | 登录、验证码、JWT 签发（RS256/JWKS）、用户/角色/菜单/字典/权限 |
| `product-master-data` | `product-master-data` | 8102 | 产品、工艺路线、机台/模具/日历、换型规则、数据版本 |
| `product-demand-service` | `product-demand` | 8103 | 客户/订单/订单行与订单生命周期、批次投影消费 |
| `product-planning` | `product-planning` | 8104 | 批次/工序任务/资源需求/派工/异步排程（快照+漂移守卫）、任务事件消费 |
| `product-execution` | `product-execution` | 8105 | 任务/资源状态事件（outbox 出站）、现场追溯 |
| `product-cloud-common` | （库，不部署） | — | 统一错误契约、请求上下文、日志基线 |
| `product-cloud-security` | （库，不部署） | — | JWT 验签核心 + JWKS + 本地验签安全链 + `@ss` 权限 |
| `product-cloud-messaging` | （库，不部署） | — | 跨服务事件一致性：outbox/幂等消费/DLX 审计/对账重放 |

本地依赖经根目录 `compose.dev.yml` 提供：Nacos、RabbitMQ、MySQL、Redis、Jaeger、ELK。

### 单体模块（14个模块，已冻结，仅作迁移对照）

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
- Docker + docker compose（本地依赖统一由 `compose.dev.yml` 提供）

### 微服务启动（现役）

1. 克隆项目

```bash
git clone <repository-url>
cd product
```

2. 准备 `.env` 并启动基础设施

```bash
cp .env.example .env
docker compose -f compose.dev.yml up -d
```

`compose.dev.yml` 提供：Nacos（`8848`，控制台 `8090`）、RabbitMQ（`5672`）、MySQL（`33066`）、Redis（`6380`）、Jaeger（OTLP `4318` / UI `16686`）与 ELK。端口均可经 `.env` 覆盖；值含空格时写成带引号形式（如 `ES_JAVA_OPTS=\"-Xms1g -Xmx1g\"`）。

3. 初始化 Nacos namespace 与数据库

```bash
# 创建 dev namespace（ID 与名称均为 dev，需在服务启动前就绪）
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/console/namespaces" \
  -d "customNamespaceId=dev&namespaceName=dev"
```

- 各服务自带可重复执行的初始化脚本：`product-services/*/src/main/resources/db/init/*_schema.sql`
- 数据库规划、服务启停顺序、密钥注入等细节见 `docs/微服务运维手册.md`

4. 构建并启动服务

```bash
mvn -pl product-services -am -DskipTests package
java -jar product-services/product-gateway/target/product-gateway-*.jar
# 其余服务同理：product-identity / product-master-data / product-demand-service / product-planning / product-execution
```

5. 访问应用

```text
网关唯一入口：http://localhost:8080
OpenAPI 聚合：/{identity|master-data|demand-svc|planning|execution}/v3/api-docs（经网关）
Jaeger：http://localhost:16686    Nacos 控制台：http://localhost:8090
```

配置隔离冒烟、事件链验证等骨架验证步骤见 `product-services/README.md` §本地启动与骨架验证。

### 单体启动（历史，仅作对照）

单体入口为 `product-server`（端口 `8081`），一键脚本：

```bash
./scripts/dev-up.sh    # 经 compose.dev.yml 启动 MySQL/Redis/ELK 等依赖，空库自动导入根目录 schema.sql
./scripts/dev-run.sh   # 以 local profile 启动单体（自动加载根目录 .env）
```

空库初始化后自带管理员账号 `admin / admin123`（首次登录后请立即修改），并写入系统管理、代码生成菜单与基础字典种子数据。也可单独启动某个依赖：

```bash
docker compose -f deploy/mysql/docker-compose.yml up -d
docker compose -f deploy/redis/docker-compose.yml up -d
docker compose -f deploy/elk/docker-compose.yml up -d
```

## 开发指南

> 以下分层与编码约定在各微服务内部同样适用；「开发新功能模块」的 Maven 模块创建流程为单体时代做法，微服务下新功能在对应服务的包结构内扩展；代码生成器为单体能力。

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

### 微服务（现役）

```bash
mvn -pl product-services -am -DskipTests package        # 构建全部微服务
mvn test -pl product-services/product-planning          # 运行单个服务的测试
java -jar product-services/product-gateway/target/product-gateway-*.jar
```

### 单体（历史）

```bash
mvn clean package -DskipTests
mvn spring-boot:run -pl product-server
./scripts/dev-up.sh
./scripts/dev-run.sh
```

### 通用

```bash
mvn dependency:tree
mvn test
```

### Git 命令

```bash
git status
git branch -a
git log --oneline -10
```

## API接口

> 以下接口均经网关 `http://localhost:8080` 访问（Phase 6 收敛路由，路径与单体保持一致）。按服务归属：认证/系统管理 → identity(8101)；产品、工艺路线、机台/日历 → master-data(8102)；客户/订单/订单行 → demand(8103)；批次/任务/派工排程 → planning(8104)；任务事件 → execution(8105)。OpenAPI 文档经网关按服务聚合。文末「代码生成」「通用功能」为单体能力，未随微服务迁移。

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

### 微服务配置（现役）

- 本地默认值在各服务 `application.yml`；远程配置经 `spring.config.import` 从 Nacos 引入（`optional:` 前缀，Nacos 不可用时可用本地默认值启动）。
- namespace = 环境短名（默认 `dev`，`NACOS_NAMESPACE` 覆盖）；服务发现 group 统一 `PRODUCT_GROUP`。
- 配置 Data ID / group 一一对应：共享配置 `product-common.yml` / `PRODUCT_COMMON`；服务专属 `product-<service>.yml` / `PRODUCT_<SERVICE>`。
- 网关 Sentinel 限流规则持久化在 Nacos（`product-gateway-flow-rules.json` / `PRODUCT_GATEWAY`），未发布时回落 application.yml 属性规则。
- 敏感注入（如 `IDENTITY_JWT_PRIVATE_KEY`）与运维配置见 `product-services/README.md` 与 `docs/微服务运维手册.md`。

### 单体配置（历史）

- `application.yml` 主配置：服务端口 8081、文件上传路径、验证码类型、Redis、Token、MyBatis、XSS 防护、登录失败锁定、OSS、排程配置（`product.pps.schedule.batch-size`、`timeout-minutes`、`timeout-scan-delay-ms`）
- `application-druid.yml` 数据源配置：数据源类型、JDBC 驱动、连接地址、账号密码、Druid 连接池参数，连接参数支持环境变量覆盖
- `mybatis-config.xml`：MyBatis 行为、日志实现、缓存与执行器配置
- `logback-spring.xml`：控制台输出、应用/错误/审计日志分流、JSON 格式与滚动策略，字段含 `traceId`、`requestId`、`userId`、`username`、`clientIp`、`httpMethod`、`requestUri`
- `generator.yml` 代码生成配置：作者名、包路径、表前缀、是否允许覆盖

### 容器化部署

- MySQL 容器部署说明：`deploy/mysql/README.md`
- Redis 部署说明：`deploy/redis/README.md`
- ELK 日志系统部署说明：`deploy/elk/README.md`
- 告警规则基线：`deploy/alerts/product-microservices-alerts.yml`

## 项目进度

> 2026-09-14 起项目整体迁移至 `product-services/` 微服务体系（见 `product-services/README.md`），以下进度以微服务体系为准；单体模块代码保留作对照。

### 已完成模块

- 基础框架搭建、系统管理、认证授权、代码生成器
- 产品与订单模块、主数据（机台、日历）管理
- 生产计划与排程（planning）、任务执行事件（execution）微服务
- Spring Cloud Alibaba 微服务迁移（phase 0-6）与事件一致性骨架（Outbox/幂等消费/死信审计/重放）

### 已实现的排程相关能力

- 生产批次管理与释放控制
- 工序任务生成与生命周期管理（取消、恢复、撤销排程）
- 基础排程与任务分配，支持四种策略（`EARLIEST_START`、`EARLIEST_FINISH`、`DUE_DATE_PRIORITY`、`LOWEST_COST`）
- 多资源排程：机台、模具、人员、工位硬约束（兼容性过滤与占用计算）、任务依赖约束
- 换型时间计算（同/异模具、换料、换色规则）
- 工艺路线闭环：规则注册表、路线 CRUD、产品绑定、`queue_policy`/标准工时模型扩展
- 任务事件追踪（开始/暂停/恢复/完工）并联动批次、订单行、订单状态
- 异步全量排程任务与进度查询、排程超时兜底扫描

### 待开发功能

- 夹具等协同资源：从领域建模到排程分配与占用计算（人员、工位已完成）
- 异常事件、报工失败等执行侧事件建模，资源状态事件驱动状态机与重排触发
- 成本模型细化（能耗、换模次数、跨班次损耗等综合因子）
- 报表统计分析
- 系统级与集成级测试（跨服务状态联动、跨班次排程、并发排程场景）

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

> 以下问题主要针对单体本地开发（`product-server`）；微服务的启动顺序、健康检查与故障处理见 `docs/微服务运维手册.md`，数据补偿见 `docs/数据补偿手册.md`。

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

- `product-services/README.md`：微服务体系模块、端口、网关路由与事件一致性说明
- `docs/`：项目文档目录
  - `docs/生产计划与排程计划系统需求说明书.md`
  - `docs/关键配置流程.md`
  - `docs/注塑件加工流程.md`
  - `docs/微服务运维手册.md`
  - `docs/数据补偿手册.md`
  - `docs/TODO.md`：待办与进展记录
- `deploy/README.md`：部署文档总索引
- `deploy/mysql/README.md`：MySQL Docker 迁移说明
- `deploy/elk/README.md`：ELK 日志系统部署说明
- `deploy/redis/README.md`：Redis 部署说明

## 贡献指南

欢迎提交 Issue 和 Pull Request。

## 许可证

本项目采用私有许可证。

## 联系方式

如有问题，请联系开发团队。

---

最后更新时间：2026-09-10
当前版本：0.0.1-SNAPSHOT
