#!/usr/bin/env bash
# Phase 5 live 验证启动脚本（全部 JVM 记录 PID 到 pids.txt；日志在 logs/）
set -u
export JAVA_HOME=~/.local/share/mise/installs/java/temurin-17.0.20+8
BASE=/home/lenny/Projects/pps/product
TS=$BASE/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase5
LOGD=$TS/logs
mkdir -p "$LOGD"
cd "$BASE"

# 快速对账节奏（补偿/自愈实测用；不影响语义，只缩小时滞）
export PLANNING_RECON_INTERVAL_MS=5000 PLANNING_RECON_INITIAL_DELAY_MS=5000
export DEMAND_RECON_INTERVAL_MS=5000 DEMAND_RECON_INITIAL_DELAY_MS=5000

start() { # name jar port
  nohup "$JAVA_HOME/bin/java" -jar "$2" --logging.file.path="$LOGD/$1" > "$LOGD/$1.console.log" 2>&1 &
  echo "$1 $! $3" >> "$TS/pids.txt"
  echo "started $1 pid=$! port=$3"
}

: > "$TS/pids.txt"
start identity        "$BASE/product-services/product-identity/target/product-identity-0.0.1-SNAPSHOT.jar" 8101
start demand-service  "$BASE/product-services/product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar" 8103
start planning        "$BASE/product-services/product-planning/target/product-planning-0.0.1-SNAPSHOT.jar" 8104
start execution       "$BASE/product-services/product-execution/target/product-execution-0.0.1-SNAPSHOT.jar" 8105
start gateway         "$BASE/product-services/product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar" 8080
echo "all started; pids in $TS/pids.txt"
