#!/usr/bin/env bash
# issue 修复验证：拉起 6 个服务（全部 PID 记录到 scratch/issue-fixes/pids.txt）
set -u
export JAVA_HOME=~/.local/share/mise/installs/java/temurin-17.0.20+8
BASE=/home/lenny/Projects/pps/product
OUT=$BASE/scratch/issue-fixes
LOGD=$OUT/logs
mkdir -p "$LOGD"
cd "$BASE"

start() { # name jar port
  nohup "$JAVA_HOME/bin/java" -jar "$2" --logging.file.path="$LOGD/$1" > "$LOGD/$1.console.log" 2>&1 &
  echo "$1 $! $3" >> "$OUT/pids.txt"
  echo "started $1 pid=$! port=$3"
}

: > "$OUT/pids.txt"
start identity        "$BASE/product-services/product-identity/target/product-identity-0.0.1-SNAPSHOT.jar" 8101
start master-data     "$BASE/product-services/product-master-data/target/product-master-data-0.0.1-SNAPSHOT.jar" 8102
start demand-service  "$BASE/product-services/product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar" 8103
start planning        "$BASE/product-services/product-planning/target/product-planning-0.0.1-SNAPSHOT.jar" 8104
start execution       "$BASE/product-services/product-execution/target/product-execution-0.0.1-SNAPSHOT.jar" 8105
start gateway         "$BASE/product-services/product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar" 8080
echo "all started; pids in $OUT/pids.txt"
