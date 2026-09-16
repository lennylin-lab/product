#!/usr/bin/env bash
# =============================================================================
# it-up.sh — 系统/集成级测试（product-integration-tests）专用 IT 栈一键启动
#
# 进程安全约束（与既有 Trellis 约定一致）：
#   - 复用健康运行中的 infra 容器（mysql/redis/nacos/rabbitmq），绝不重启用户栈；
#   - IT 服务实例使用独立端口 8201-8205 / 8280（用户栈 8101-8105 / 8080 不触碰）；
#   - PID 只记录在 $BASE/scratch/integration-tests/pids.txt，it-down.sh 只杀这些 PID。
#
# 四件套隔离设计（与用户常驻栈共存，见模块 README「隔离设计」）：
#   - Nacos：服务注册/发现用独立 group PRODUCT_IT_GROUP（双栈互不可见，lb:// 各自成环）；
#   - Redis：SPRING_DATA_REDIS_DATABASE=5（用户栈 db 0；锁/标记/验证码/令牌全隔离）；
#   - RabbitMQ：独立 vhost it（exchange/queue/DLX 自声明，事件互不投递）；
#   - MySQL：共享实例 + 服务库（黑盒断言需真实数据面）；套件双向清理 + 行数校验兜底。
# =============================================================================
set -euo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SVCD="$BASE/product-services"
OUT="$BASE/scratch/integration-tests"
LOGD="$OUT/logs"
PIDS="$OUT/pids.txt"
mkdir -p "$LOGD"

# ---- JDK（平台注记：mise 默认 17.0.2 有 cgroup v2 崩溃坑，用 temurin 17.0.20+） ----
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+8}"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || { echo "ERROR: java 不存在: $JAVA（可用 JAVA_HOME 覆盖）"; exit 1; }

# ---- 凭据（与 compose.dev.yml/.env.example 基线一致；可用环境变量覆盖） ----
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"
REDIS_PASSWORD="${REDIS_PASSWORD:-123456}"
RABBITMQ_VHOST_NAME="${RABBITMQ_VHOST_NAME:-it}"
IT_GROUP="${IT_GROUP:-PRODUCT_IT_GROUP}"
GW_JWKS="${GW_JWKS:-http://127.0.0.1:8201/jwks}"

mysql_exec() { docker exec product-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$@"; }

echo "== [1/6] infra 容器检查（健康则复用，不触碰用户运行中的容器） =="
need_compose=0
for c in product-mysql product-redis product-nacos product-rabbitmq; do
  st="$(docker inspect -f '{{.State.Running}} {{.State.Health.Status}}' "$c" 2>/dev/null || echo 'missing')"
  if [ "$st" != "true healthy" ]; then echo "  $c 不可用（$st）→ 需要 compose 拉起"; need_compose=1; else echo "  $c healthy → 复用"; fi
done
if [ "$need_compose" = "1" ]; then
  # compose.dev.yml 不在 docker compose 默认搜索列表，必须显式 -f（CI 首跑踩坑）
  docker compose -f "$BASE/compose.dev.yml" up -d nacos rabbitmq mysql redis
  echo "  等待容器健康…"
  for i in $(seq 1 60); do
    ok=1
    for c in product-mysql product-redis product-nacos product-rabbitmq; do
      st="$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)"
      [ "$st" = "healthy" ] || { ok=0; break; }
    done
    [ "$ok" = "1" ] && break
    sleep 5
  done
  [ "$ok" = "1" ] || { echo "ERROR: infra 容器未在超时内转为 healthy"; exit 1; }
fi

echo "== [2/6] RabbitMQ vhost '$RABBITMQ_VHOST_NAME'（事件面隔离） =="
if ! docker exec product-rabbitmq rabbitmqctl list_vhosts | grep -qx "$RABBITMQ_VHOST_NAME"; then
  docker exec product-rabbitmq rabbitmqctl add_vhost "$RABBITMQ_VHOST_NAME"
  docker exec product-rabbitmq rabbitmqctl set_permissions -p "$RABBITMQ_VHOST_NAME" guest ".*" ".*" ".*"
  echo "  vhost 已创建并授权 guest"
else
  echo "  vhost 已存在"
fi

echo "== [3/6] 服务库 schema（已存在则保留，绝不重置用户数据面） =="
missing=0
for db in identity_db master_data_db demand_db planning_db execution_db; do
  n="$(mysql_exec -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$db'")"
  [ "$n" = "1" ] || { echo "  缺库: $db"; missing=1; }
done
if [ "$missing" = "1" ]; then
  echo "  应用各服务 db/init 初始化脚本（含 DROP+CREATE+种子，仅对缺失库执行）…"
  mysql_exec -e "SELECT 1" >/dev/null
  apply_one() { # db file
    local db="$1" file="$2"
    local n="$(mysql_exec -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$db'")"
    if [ "$n" != "1" ]; then
      echo "  init $db <- $file"
      docker exec -i product-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 < "$file"
    fi
  }
  apply_one identity_db     "$SVCD/product-identity/src/main/resources/db/init/identity_schema.sql"
  apply_one master_data_db  "$SVCD/product-master-data/src/main/resources/db/init/master_data_schema.sql"
  apply_one demand_db       "$SVCD/product-demand-service/src/main/resources/db/init/demand_schema.sql"
  apply_one planning_db     "$SVCD/product-planning/src/main/resources/db/init/planning_schema.sql"
  apply_one execution_db    "$SVCD/product-execution/src/main/resources/db/init/execution_schema.sql"
else
  echo "  5 个服务库齐全 → 保留现状（含用户数据）"
fi

echo "== [4/6] 服务 fat-jar（缺则现场打包 -DskipTests） =="
declare -A JARS=(
  [identity]="product-identity/target/product-identity-0.0.1-SNAPSHOT.jar"
  [master-data]="product-master-data/target/product-master-data-0.0.1-SNAPSHOT.jar"
  [demand-service]="product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar"
  [planning]="product-planning/target/product-planning-0.0.1-SNAPSHOT.jar"
  [execution]="product-execution/target/product-execution-0.0.1-SNAPSHOT.jar"
  [gateway]="product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar"
)
build_needed=0
for m in "${!JARS[@]}"; do
  [ -f "$SVCD/${JARS[$m]}" ] || build_needed=1
done
if [ "$build_needed" = "1" ] || [ "${IT_BUILD:-0}" = "1" ]; then
  ( cd "$SVCD" && mvn -B -ntp -DskipTests package )
fi

echo "== [5/6] 启动 6 个服务实例（独立端口 + 四件套隔离） =="
find "$LOGD" -name '.up' -delete 2>/dev/null || true
: > "$PIDS"

start() { # name jar-rel-path port extra-env...
  local name="$1" jar="$2" port="$3"; shift 3
  local log="$LOGD/$name"
  mkdir -p "$log"
  env "$@" \
    "$JAVA" -jar "$SVCD/$jar" \
      --server.port="$port" \
      --spring.cloud.nacos.discovery.group="$IT_GROUP" \
      --spring.cloud.nacos.discovery.register-enabled=true \
      --logging.file.path="$log" \
      > "$log/console.log" 2>&1 &
  local pid=$!
  echo "$name $pid $port" >> "$PIDS"
  echo "  started $name pid=$pid port=$port"
}

# 公共隔离环境变量（全部实例）：JWKS 指向 IT identity、Redis db5、RabbitMQ vhost it
COMMON_ENV=(
  PRODUCT_SECURITY_JWKS_URI="$GW_JWKS"
  SPRING_DATA_REDIS_HOST="127.0.0.1" SPRING_DATA_REDIS_PORT="6380"
  SPRING_DATA_REDIS_PASSWORD="$REDIS_PASSWORD" SPRING_DATA_REDIS_DATABASE="5"
  RABBITMQ_HOST="127.0.0.1" RABBITMQ_AMQP_PORT="5672" RABBITMQ_VHOST="$RABBITMQ_VHOST_NAME"
)

start identity       "${JARS[identity]}"       8201 "${COMMON_ENV[@]}"
start master-data    "${JARS[master-data]}"    8202 "${COMMON_ENV[@]}"
start demand-service "${JARS[demand-service]}" 8203 "${COMMON_ENV[@]}"
# planning：服务身份令牌指向 IT identity；sweeper 节拍缩短到 1s（R4 排空场景依赖）
start planning       "${JARS[planning]}"       8204 "${COMMON_ENV[@]}" \
  PLANNING_SERVICE_IDENTITY_TOKEN_URL="http://127.0.0.1:8201/internal/identity/service-token" \
  PRODUCT_PPS_SCHEDULE_TIMEOUT_SCAN_DELAY_MS="1000"
start execution      "${JARS[execution]}"      8205 "${COMMON_ENV[@]}"
start gateway        "${JARS[gateway]}"        8280 "${COMMON_ENV[@]}"

echo "== [6/6] 健康就绪等待（/actuator/health，最长 180s） =="
declare -A URLS=(
  [identity]=http://127.0.0.1:8201 [master-data]=http://127.0.0.1:8202
  [demand-service]=http://127.0.0.1:8203 [planning]=http://127.0.0.1:8204
  [execution]=http://127.0.0.1:8205 [gateway]=http://127.0.0.1:8280
)
for i in $(seq 1 90); do
  pending=0
  for name in "${!URLS[@]}"; do
    [ -f "$LOGD/$name/.up" ] && continue
    if curl -fsS "${URLS[$name]}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      touch "$LOGD/$name/.up"; echo "  $name UP"
    else
      pending=1
    fi
  done
  [ "$pending" = "0" ] && break
  sleep 2
done
for name in "${!URLS[@]}"; do
  [ -f "$LOGD/$name/.up" ] || { echo "ERROR: $name 未在超时内就绪（日志: $LOGD/$name/console.log）"; exit 1; }
done

echo ""
echo "IT 栈就绪：gateway=8280 identity=8201 master-data=8202 demand=8203 planning=8204 execution=8205"
echo "pids: $PIDS | logs: $LOGD"
echo "运行套件: cd $SVCD && mvn -B -ntp -pl product-integration-tests verify"
