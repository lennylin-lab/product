# Redis Docker 迁移说明

## 目标

把当前项目使用的 Redis 从宿主机迁到 Docker 容器，并沿用和 ELK、MySQL 一样的 `docker compose` 部署方式。

## 可行性评估

可以迁移，而且比 MySQL 简单很多。

- 项目里 Redis 的统一入口在 `product-server/src/main/resources/application.yml`。
- `product-cache` 中的 `RedisTemplate`、`StringRedisTemplate`、`RedissonClient` 都会读取这个配置。
- `RedisCache` 和 `RedisDistributedLock` 目前都依赖这个连接参数，所以只要容器地址切换正确，业务代码不需要改。

## 目录约定

- `docker-compose.yml`: Redis 容器编排
- `.env`: 单独启动 Redis 时使用的环境变量模板
- `redis.conf`: 额外的 Redis 配置，如果后续需要细调可放这里
- `data/`: Redis 持久化目录

## 迁移策略

建议采用“先起容器，再切连接”的方式。

1. 确认本机当前 Redis 端口

```bash
ss -lntp | grep 6380
```

2. 准备容器参数

仓库中已提供 `deploy/redis/.env`，单独启动 Redis 时会自动读取它。默认内容如下：

```dotenv
TZ=Asia/Taipei
REDIS_PORT=6380
REDIS_PASSWORD=123456
REDIS_MAXMEMORY=256mb
REDIS_MAXMEMORY_POLICY=allkeys-lru
REDIS_DATABASES=16
```

3. 启动 Redis 容器

```bash
docker compose -f deploy/redis/docker-compose.yml up -d
```

如果宿主机已经占用 `6380`，可以临时改端口：

```bash
REDIS_PORT=6381 docker compose -f deploy/redis/docker-compose.yml up -d
```

4. 验证 Redis 可用

```bash
docker exec -it product-redis redis-cli -a "${REDIS_PASSWORD}" ping
```

正常会返回：

```bash
PONG
```

5. 切换应用连接

后端运行在宿主机时，推荐直接覆盖环境变量：

```bash
export SPRING_DATA_REDIS_HOST=127.0.0.1
export SPRING_DATA_REDIS_PORT=6380
export SPRING_DATA_REDIS_PASSWORD=change-me-in-env
export SPRING_DATA_REDIS_DATABASE=0
```

如果 Redis 容器映射的是其他端口，把 `SPRING_DATA_REDIS_PORT` 一起改掉即可。

6. 启动后端并回归验证

```bash
mvn spring-boot:run -pl product-server -Dspring.profiles.active=dev
```

重点验证：

- 登录验证码
- 登录失败次数限制
- 防重复提交
- 排程锁
- 缓存刷新

## 切换窗口建议

- 先保留宿主机 Redis，不要立刻停掉
- 新容器启动后先做读写验证
- 应用切换后观察一段时间
- 确认无误再关闭旧 Redis

## 风险点

- `appendonly yes` 会开启 AOF 持久化，磁盘占用会比纯内存模式高一些，但更适合业务缓存。
- 如果你后面把应用也容器化，`SPRING_DATA_REDIS_HOST` 可以直接改成 `redis`，连宿主机端口都不需要暴露。
- 现有默认密码仍是明文，建议后续统一放到 `.env` 或部署环境变量里。

## 与根目录 `.env` 的关系

- 根目录 `.env`：用于本地开发一键启动，`./scripts/dev-up.sh` 和 `./scripts/dev-run.sh` 都会读取。
- `deploy/redis/.env`：用于单独执行 `docker compose -f deploy/redis/docker-compose.yml up -d` 时的独立配置。
- 如果你希望两者保持一致，优先修改根目录 `.env`，再按需同步到 `deploy/redis/.env`。

## 是否要并入 ELK 的 compose

技术上可以，但默认不建议。

- 适合合并：本机开发环境，需要一键拉起全套依赖。
- 不适合合并：Redis 需要更频繁重启、调参数、清缓存时，和 ELK 的生命周期不一致。

更稳妥的方式是：

- `deploy/redis/docker-compose.yml` 独立管理 Redis
- `deploy/mysql/docker-compose.yml` 独立管理 MySQL
- `deploy/elk/docker-compose.yml` 独立管理日志系统
