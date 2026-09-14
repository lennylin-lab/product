# sca-verify — Phase 0 版本基线最小验证应用

独立于 product 工程的最小验证应用（不得引入业务依赖，不得并入 product 根 POM）。用途：实证 ADR-0001 版本基线的可启动性与组件可用性。

## 版本组合（验证对象）

- JDK 17（本机 `mise` 安装的 17.0.2）
- Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Spring Cloud Alibaba 2025.0.0.0（父 POM 三 BOM import）
- 中间件：Nacos `nacos/nacos-server:v3.0.3`、RabbitMQ `rabbitmq:3.13-management`

## 模块

| 模块 | 端口 | 验证点 |
| --- | --- | --- |
| verify-provider | 18082 | Web + Nacos Discovery 注册 |
| verify-core | 18081 | Nacos Config（`spring.config.import`）、OpenFeign + LoadBalancer、Sentinel（@SentinelResource + 流控规则）、Spring AMQP（持久化队列/交换机、发送/消费） |
| verify-gateway | 18080 | Spring Cloud Gateway（`lb://` 路由）+ Nacos Discovery + Sentinel 网关适配（`spring-cloud-alibaba-sentinel-gateway`） |

## 复现步骤

```bash
# 0. 中间件（Nacos v3 镜像必须提供 AUTH_TOKEN/IDENTITY 环境，即使 AUTH_ENABLE=false）
docker run -d --name sca-verify-nacos -e MODE=standalone -e NACOS_AUTH_ENABLE=false \
  -e NACOS_AUTH_TOKEN=$(echo -n "SecretKey012345678901234567890123456789012345678901234567890123456789" | base64 -w0) \
  -e NACOS_AUTH_IDENTITY_KEY=serverIdentity -e NACOS_AUTH_IDENTITY_VALUE=sca-verify \
  -p 8848:8848 -p 9848:9848 -p 18090:8080 nacos/nacos-server:v3.0.3
docker run -d --name sca-verify-rabbitmq -p 35672:5672 -p 45672:15672 rabbitmq:3.13-management

# 1. 发布配置中心属性（v1 兼容 OpenAPI）
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  -d "dataId=verify-core.properties&group=DEFAULT_GROUP&content=verify.greeting=nacos-config-works-phase0"

# 2. 构建（JDK 17）
JAVA_HOME=~/.local/share/mise/installs/java/17.0.2 mvn -B clean package

# 3. 依次启动三个 jar（logs 到 /tmp/verify-*.log）

# 4. 断言
curl "http://127.0.0.1:18081/feign/hello?name=phase0"    # -> hello, phase0 (from verify-provider)
curl "http://127.0.0.1:18081/config/greeting"            # -> nacos-config-works-phase0
for i in 1 2 3 4; do curl -s "http://127.0.0.1:18081/sentinel/ping"; echo; done
                                                          # -> pong x2, blocked-by-sentinel x2 (QPS=2)
curl -X POST "http://127.0.0.1:18081/amqp/send?text=e1"; sleep 2
curl "http://127.0.0.1:18081/amqp/received"               # -> ["e1"]
curl "http://127.0.0.1:18081/discovery/services"          # -> ["verify-core","verify-gateway","verify-provider"]
curl "http://127.0.0.1:18080/provider/hello?name=gw"      # -> hello, gw (from verify-provider)

# 5. 清理
pkill -f 'target/verify-.*jar'; docker rm -f sca-verify-nacos sca-verify-rabbitmq
```

## 2026-09-14 实测结果（全部通过）

- `mvn clean package`：BUILD SUCCESS（JDK 17.0.2, Maven 3.9.16）。
- `mvn dependency:tree -Dverbose=true`：0 个 `omitted for conflict`；spring-web 归一 6.2.19、nacos-client 3.0.3、sentinel-core 1.8.9。
- 三应用启动成功（约 5–7 秒），日志出现 `NacosServiceRegistry : nacos registry ... register finished`。
- 上表 6 项断言全部符合预期；应用日志无 ERROR。
- 注意事项：Boot repackage 在无 `spring-boot-starter-parent` 时需显式 `<execution><goals><goal>repackage</goal></goals></execution>`（本父 POM 已配置）；Sentinel 网关适配必须用 `spring-cloud-alibaba-sentinel-gateway`（旧 adapter 不在 SCA 2025.0.0.0 BOM 内）。
