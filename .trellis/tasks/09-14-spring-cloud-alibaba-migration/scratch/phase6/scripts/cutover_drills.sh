#!/usr/bin/env bash
# Phase 6 演练：离线备份（mysqldump 各库）+ 数据校验 + 恢复 + 整套回滚。
# 用法:
#   cutover_drills.sh backup     -- 备份 product(单体旧库) + 5 个服务库 -> backups/
#   cutover_drills.sh restore    -- 从备份恢复全部库（drop -> create -> import -> 计数核对）
#   cutover_drills.sh rollback   -- 整套回滚演练：关微服务入口 -> 恢复旧库备份 -> 单体可用冒烟
set -u
BASE=/home/lenny/Projects/pps/product
P6=$BASE/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6
BK=$P6/backups
JARS=$BASE/product-services
JAVA=/home/lenny/.local/share/mise/installs/java/temurin-17.0.20+8/bin/java
DBS="product identity_db master_data_db demand_db planning_db execution_db"
mkdir -p "$BK"
MYSQL_DUMP=(docker exec product-mysql mysqldump -uroot -p123456 --single-transaction --routines --events --set-gtid-purged=OFF)
MYSQL=(docker exec -i product-mysql mysql -uroot -p123456)

count_all() { # 每库表清单快照（InnoDB table_rows 为估算值，恢复比对只看表清单；数据以精确计数复核）
  docker exec product-mysql mysql -uroot -p123456 -N -B -e "
    SELECT table_schema, table_name FROM information_schema.tables
    WHERE table_schema IN ('product','identity_db','master_data_db','demand_db','planning_db','execution_db')
      AND table_type='BASE TABLE'
    ORDER BY table_schema, table_name;"
}

do_backup() {
  echo "== [backup] 备份 6 库 -> $BK =="
  for db in $DBS; do
    "${MYSQL_DUMP[@]}" "$db" > "$BK/${db}.sql" 2>/dev/null \
      && echo "  OK $db -> ${db}.sql ($(du -h "$BK/${db}.sql" | cut -f1))" \
      || { echo "  FAIL $db"; exit 1; }
  done
  count_all > "$BK/tables_before_restore.txt"
  echo "  snapshot: tables_before_restore.txt ($(wc -l < "$BK/tables_before_restore.txt") tables)"
}

do_restore() {
  echo "== [restore] 从备份恢复 6 库 =="
  for db in $DBS; do
    docker exec product-mysql mysql -uroot -p123456 -N -B -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    "${MYSQL[@]}" "$db" < "$BK/${db}.sql" \
      && echo "  OK $db restored" || { echo "  FAIL $db"; exit 1; }
  done
  count_all > "$BK/tables_after_restore.txt"
  echo "-- 表清单比对（恢复前后）--"
  if diff "$BK/tables_before_restore.txt" "$BK/tables_after_restore.txt" > /dev/null; then
    echo "  PASS 6 库表清单一致（$(wc -l < "$BK/tables_after_restore.txt") 张表）"
  else
    echo "  FAIL 表清单不一致："
    diff "$BK/tables_before_restore.txt" "$BK/tables_after_restore.txt" | head -10
    exit 1
  fi
  echo "-- 精确计数复核（关键表）--"
  for q in "product:SELECT COUNT(*) FROM sys_user" \
           "identity_db:SELECT COUNT(*) FROM sys_user" \
           "identity_db:SELECT COUNT(*) FROM sys_menu" \
           "master_data_db:SELECT COUNT(*) FROM product" \
           "demand_db:SELECT COUNT(*) FROM customer_order" \
           "demand_db:SELECT COUNT(*) FROM order_line" \
           "planning_db:SELECT COUNT(*) FROM production_batch" \
           "planning_db:SELECT COUNT(*) FROM operation_task" \
           "execution_db:SELECT COUNT(*) FROM task_event"; do
    db="${q%%:*}"; stmt="${q#*:}"
    echo "  $db: $stmt -> $(docker exec product-mysql mysql -uroot -p123456 -N -B "$db" -e "$stmt")"
  done
}

do_rollback() {
  echo "== [rollback] 整套回滚演练 =="
  echo "-- [R1] 关闭微服务入口（全部 6 个 JVM）--"
  while read -r name pid port; do
    if kill -0 "$pid" 2>/dev/null; then kill "$pid" && echo "  stopped $name pid=$pid"; fi
  done < "$P6/pids.txt"
  sleep 8
  for port in 8080 8101 8102 8103 8104 8105; do
    ss -tln | grep -q ":$port " && echo "  WARN port $port still listening" || echo "  port $port free"
  done

  echo "-- [R2] 恢复旧库备份（6 库 drop -> create -> import）--"
  do_restore

  echo "-- [R3] 单体可用：构建 product-server 并冒烟 =="
  cd "$BASE"
  export JAVA_HOME=/home/lenny/.local/share/mise/installs/java/temurin-17.0.20+8
  # 单体为普通 jar 打包（无 boot manifest），按单体既有运行方式以 spring-boot:run 启动
  mvn -B -ntp -pl product-server -am -DskipTests package -q > "$P6/logs/rollback_mono_build.log" 2>&1 \
    && echo "  单体构建 OK（mvn package，日志 rollback_mono_build.log）" \
    || { echo "  单体构建 FAIL"; exit 1; }
  SPRING_DATA_REDIS_PASSWORD=123456 nohup mvn -B -ntp -pl product-server spring-boot:run \
    > "$P6/logs/rollback_mono.console.log" 2>&1 &
  MONO_PID=$!
  echo "  单体启动 pid=$MONO_PID（8081）..."
  ok=0
  for i in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8081/captchaImage || true)
    [ "$code" = "200" ] && ok=1 && break
    sleep 2
  done
  echo "  单体 /captchaImage -> $code (ok=$ok)"
  if [ "$ok" = "1" ]; then
    info=$(curl -s -X POST http://127.0.0.1:8081/login -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin123","code":"","uuid":""}')
    echo "  单体登录接口可达（验证码空值 -> 预期校验失败响应）: $(echo "$info" | head -c 80)"
  fi
  echo "-- [R4] 单体冒烟完成，停止单体（演练不要求长期运行）--"
  # spring-boot:run：杀 mvn 父进程树（子进程 java 由 mvn 启动）
  pkill -P "$MONO_PID" 2>/dev/null
  kill "$MONO_PID" 2>/dev/null
  sleep 5
  ss -tln | grep -q ":8081 " && { pkill -f "product-server" 2>/dev/null; sleep 3; } || true
  ss -tln | grep -q ":8081 " && echo "  WARN 8081 still listening" || echo "  单体已停止，8081 释放"
  [ "$ok" = "1" ] && echo "ROLLBACK DRILL PASS" || { echo "ROLLBACK DRILL FAIL"; exit 1; }
}

case "${1:-}" in
  backup)  do_backup ;;
  restore) do_restore ;;
  rollback) do_rollback ;;
  *) echo "usage: $0 backup|restore|rollback"; exit 1 ;;
esac
