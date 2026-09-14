#!/usr/bin/env bash
# Phase 6 clean-room 启动演练：从零起基础设施 -> 建 namespace -> 发布 Sentinel 规则 ->
# 执行全部服务库 schema 初始化脚本 -> 启动 6 个微服务（OTLP 导出打开）-> 冒烟断言。
# 全程 PID 记录到 phase6/pids.txt（只清理自己启动的进程）。
set -u
export JAVA_HOME=~/.local/share/mise/installs/java/temurin-17.0.20+8
BASE=/home/lenny/Projects/pps/product
TASK=$BASE/.trellis/tasks/09-14-spring-cloud-alibaba-migration
P6=$TASK/scratch/phase6
LOGD=$P6/logs
JARS=$BASE/product-services
cd "$BASE"
mkdir -p "$LOGD"

echo "== [1] 基础设施（nacos/rabbitmq/jaeger 经 compose.dev.yml；mysql/redis 为既有实例） =="
docker compose -f compose.dev.yml up -d nacos rabbitmq jaeger
for c in product-nacos product-rabbitmq product-jaeger; do
  for i in $(seq 1 60); do
    st=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo starting)
    [ "$st" = healthy ] && break
    sleep 2
  done
  echo "  $c health=$st"
done

echo "== [2] Nacos dev namespace（v3 admin API；无卷持久化，每次 clean-room 重建） =="
curl -s -X POST "http://127.0.0.1:8848/nacos/v3/admin/core/namespace" \
  -H "serverIdentity: product-dev" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "namespaceId=dev&namespaceName=dev"; echo

echo "== [3] Sentinel 网关流控规则发布到 Nacos（Phase 6 遗留收口：sentinel-datasource） =="
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  -d "tenant=dev&dataId=product-gateway-flow-rules.json&group=PRODUCT_GATEWAY&type=json" \
  --data-urlencode 'content=[
  {"resource":"identity-auth","grade":1,"count":200},
  {"resource":"identity-system","grade":1,"count":200},
  {"resource":"demand-business","grade":1,"count":200},
  {"resource":"planning-assignment","grade":1,"count":200}
]'; echo

echo "== [4] 服务库 schema 初始化（5 个脚本，可重复执行=重置回种子空库） =="
for s in "product-identity/src/main/resources/db/init/identity_schema.sql" \
         "product-master-data/src/main/resources/db/init/master_data_schema.sql" \
         "product-demand-service/src/main/resources/db/init/demand_schema.sql" \
         "product-planning/src/main/resources/db/init/planning_schema.sql" \
         "product-execution/src/main/resources/db/init/execution_schema.sql"; do
  docker exec -i product-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < "$JARS/$s" \
    && echo "  OK $s" || { echo "  FAIL $s"; exit 1; }
done

echo "== [5] 启动 6 个微服务（OTLP_TRACES_ENABLED=true -> jaeger :4318） =="
export OTLP_TRACES_ENABLED=true
export OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces
# 快速对账节奏（漂移自愈实测用；不影响语义）
export PLANNING_RECON_INTERVAL_MS=5000 PLANNING_RECON_INITIAL_DELAY_MS=5000
export DEMAND_RECON_INTERVAL_MS=5000 DEMAND_RECON_INITIAL_DELAY_MS=5000
: > "$P6/pids.txt"
start() { # name jar port
  nohup "$JAVA_HOME/bin/java" -jar "$2" --logging.file.path="$LOGD/$1" > "$LOGD/$1.console.log" 2>&1 &
  echo "$1 $! $3" >> "$P6/pids.txt"
  echo "  started $1 pid=$! port=$3"
}
start identity     "$JARS/product-identity/target/product-identity-0.0.1-SNAPSHOT.jar" 8101
start master-data  "$JARS/product-master-data/target/product-master-data-0.0.1-SNAPSHOT.jar" 8102
start demand       "$JARS/product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar" 8103
start planning     "$JARS/product-planning/target/product-planning-0.0.1-SNAPSHOT.jar" 8104
start execution    "$JARS/product-execution/target/product-execution-0.0.1-SNAPSHOT.jar" 8105
start gateway      "$JARS/product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar" 8080

echo "== [6] 就绪等待 + 冒烟断言 =="
ok=0
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8080/captchaImage || true)
  [ "$code" = "200" ] && ok=1 && break
  sleep 2
done
echo "  gateway /captchaImage -> $code (ok=$ok)"
for p in 8101 8102 8103 8104 8105; do
  echo "  health $p: $(curl -s --max-time 2 http://127.0.0.1:$p/actuator/health)"
done
echo "  nacos services: $(curl -s 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&namespaceId=dev&groupName=PRODUCT_GROUP')"
echo "clean-room up done"
