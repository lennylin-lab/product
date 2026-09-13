# ELK 日志系统部署指南

## 文件说明

- `docker-compose.yml`: 启动 Elasticsearch、Kibana、Logstash 和 Filebeat 服务
- `.env`: 单独启动 ELK 时使用的环境变量模板
- `filebeat/filebeat.yml`: 采集 `product-server` 产生的 JSON 格式日志
- `logstash/pipeline/logstash.conf`: 日志事件规范化处理并写入 Elasticsearch

## 日志路径

应用当前将 JSON 日志写入以下目录：

- `/home/chuan/project/backend/logs`

`docker-compose.yml` 通过以下环境变量将该路径挂载到 Filebeat 容器：

- `PRODUCT_LOG_PATH`

仓库中已提供 `deploy/elk/.env`，单独启动 ELK 时会自动读取它。默认内容如下：

```dotenv
PRODUCT_LOG_PATH=./logs
SERVICE_NAME=product-server
APP_ENV=local
ELASTICSEARCH_PORT=9201
KIBANA_PORT=5601
LOGSTASH_BEATS_PORT=5044
LOGSTASH_API_PORT=9600
ES_JAVA_OPTS=-Xms1g -Xmx1g
LS_JAVA_OPTS=-Xms512m -Xmx512m
```

如果你的运行时日志路径发生变化，可通过以下方式启动 compose：

```bash
PRODUCT_LOG_PATH=/your/log/path docker compose -f deploy/elk/docker-compose.yml up -d
```

可选环境变量：

- `SERVICE_NAME`: 默认值为 `product-server`
- `APP_ENV`: 默认值为 `local`

## 启动服务

```bash
SERVICE_NAME=product-server APP_ENV=dev \
docker compose -f deploy/elk/docker-compose.yml up -d
```

## 验证服务

### Elasticsearch

```bash
curl http://localhost:9201/_cat/indices?v
```

### Kibana

1. 打开浏览器访问: `http://localhost:5601`
2. 创建索引模式: `product-*`

### Logstash

```bash
curl http://localhost:9600
```

## 预期索引

启动后，Elasticsearch 中将创建以下索引：

- `product-application-*` - 应用日志
- `product-error-*` - 错误日志
- `product-audit-*` - 审计日志

## 注意事项

- 当前配置 `xpack.security.enabled=false`，仅适用于本地开发环境
- 生产环境部署时，请启用安全认证、设置密码，并将 Elasticsearch 数据存储在持久化存储上
- Filebeat 假设每行一个 JSON 事件，与当前的 `logback-spring.xml` 配置匹配

## 快速排查

### 服务启动失败

```bash
# 查看服务状态
docker compose -f deploy/elk/docker-compose.yml ps

# 查看服务日志
docker compose -f deploy/elk/docker-compose.yml logs [服务名]
```

### 日志未采集

1. 检查日志文件路径是否正确挂载
2. 确认日志文件格式为 JSON（每行一个 JSON 对象）
3. 查看 Filebeat 日志：`docker compose -f deploy/elk/docker-compose.yml logs filebeat`

### Kibana 无法显示数据

1. 确认索引已创建：`curl http://localhost:9201/_cat/indices?v`
2. 检查索引模式是否正确配置：`product-*`
3. 等待 1-2 分钟让数据索引完成

## 与根目录 `.env` 的关系

- 根目录 `.env`：用于本地开发一键启动，`./scripts/dev-up.sh` 会同时拉起 `MySQL + Redis + ELK`，`./scripts/dev-run.sh` 会用同一份配置启动 Spring Boot。
- `deploy/elk/.env`：用于单独执行 `docker compose -f deploy/elk/docker-compose.yml up -d` 时的独立配置。
- 如果你希望日志路径、端口和 JVM 参数保持一致，优先修改根目录 `.env`，再按需同步到 `deploy/elk/.env`。
