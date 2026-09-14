# ADR-0001: 版本基线（JDK / Spring Boot / Spring Cloud / Spring Cloud Alibaba）

- 状态: Accepted（已经 2026-09-14 最小启动验证）
- 关联: design.md §3 Infrastructure Choices、implement.md Phase 0

## 背景

项目现状：Java 17（父 POM `java.version=17`、compiler source/target 17）、Spring Boot 3.5.0（`spring-boot-dependencies` BOM import，非 `spring-boot-starter-parent`）、MyBatis-Plus 3.5.9、springdoc 2.8.13。尚无 Spring Cloud / Spring Cloud Alibaba 依赖管理。

PRD R1 要求：版本组合必须以官方兼容矩阵为准，并用最小验证应用实际启动验证，不凭经验硬编码。

## 官方兼容矩阵结论（2026-09-14 查证）

来源：`github.com/alibaba/spring-cloud-alibaba` README 分支对照表与 Releases 页、Maven Central 元数据。

| SCA 分支 | Spring Cloud | Spring Boot | JDK |
| --- | --- | --- | --- |
| 2025.1.x | 2025.1.x | 4.0.x | 17+ |
| **2025.0.x** | **2025.0.x** | **3.5.x** | **17+** |
| 2023.x | 2023.x | 3.2.x | 17+ |

该线最新稳定 release 为 **Spring Cloud Alibaba 2025.0.0.0**（依赖 Spring Boot 3.5.0、Spring Cloud 2025.0.0、nacos-client 3.0.3、Sentinel 1.8.9）。SCA 2025.1.0.0 面向 Boot 4.0.x，本任务不采用。Maven Central 上 Spring Cloud 2025.0.x 训练线最新补丁为 2025.0.3；Boot 3.5.x 最新补丁为 3.5.16。

## 决策

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17（最低线） | 项目与 SCA 均以 17 为基线；不强制升 21，后续可单独评估 |
| Spring Boot | 3.5.x，锁定 3.5.16（3.5 线最新补丁） | Phase 1 重组父 POM 时从 3.5.0 升到当前补丁；3.5.x 线内补丁升级视为安全 |
| Spring Cloud | 2025.0.3（`spring-cloud-dependencies` BOM import） | 与 SCA 2025.0.0.0 同训练线；取训练线最新补丁 |
| Spring Cloud Alibaba | 2025.0.0.0（`spring-cloud-alibaba-dependencies` BOM import） | 官方组合：Boot 3.5.x ↔ SC 2025.0.x ↔ SCA 2025.0.x |
| Nacos Server | 3.0.x（验证用 v3.0.3，与 nacos-client 3.0.3 同大版本） | Docker 镜像 `nacos/nacos-server:v3.0.3` |
| Sentinel | 1.8.9（SCA BOM 管理） | 网关适配使用 `sentinel-spring-cloud-gateway-v6x-adapter`（经 `com.alibaba.cloud:spring-cloud-alibaba-sentinel-gateway` 引入；旧 `sentinel-spring-cloud-gateway-adapter` 不在 2025.0.0.0 BOM 管理内，禁用） |
| RabbitMQ / Spring AMQP | rabbitmq:3.13-management + Boot BOM 管理的 spring-amqp | 与现有镜像资产一致 |

版本管理方式沿用项目现状：父 POM `dependencyManagement` 依次 import 三个 BOM（Boot → Cloud → Cloud Alibaba），不使用 `spring-boot-starter-parent`。

## 验证记录（实际执行）

最小验证应用位于 `scratch/sca-verify/`（3 个模块：verify-provider / verify-core / verify-gateway，独立于 product 构建）。JDK 17.0.2、Maven 3.9.16、Docker 容器 nacos v3.0.3 + rabbitmq 3.13。

1. `mvn clean package`：BUILD SUCCESS。
2. `mvn dependency:tree -Dverbose=true`：无 `omitted for conflict`；全部冲突由 BOM 归一（spring-web 6.2.19、nacos-client 3.0.3、sentinel-core 1.8.9）。
3. 实机启动与功能验证全部通过：
   - 三服务注册 Nacos 成功（`register finished` 日志）；
   - Nacos Config 远程属性加载成功（`/config/greeting` 返回配置中心值）；
   - OpenFeign 经 LoadBalancer 调用 verify-provider 成功；
   - Sentinel QPS=2 流控生效（第 3 次请求起返回 blockHandler 结果）；
   - Spring AMQP 声明持久化队列/交换机、发送并被消费者收到（`["e1","e2"]`）；
   - Gateway `lb://verify-provider` 路由成功。
4. Boot 3.5.16 + SCA 2025.0.0.0（SCA 认证组合为 3.5.0）跨补丁运行无异常。

复现步骤见 `scratch/sca-verify/README.md`。

## 后果与约束

- 后续所有 Phase 以此基线为准；升级 SCA 大版本（如 2025.1.x/Boot 4）需重新执行本验证流程。
- Spring Cloud Gateway 一律使用 `spring-cloud-starter-gateway-server-webflux`（SC 2025.0.x 命名）；Sentinel 网关适配必须使用 SCA 的 `spring-cloud-alibaba-sentinel-gateway` 模块。
- Nacos 服务端保持 3.0.x 大版本与 nacos-client 对齐；镜像随 SCA 升级同步评审。
