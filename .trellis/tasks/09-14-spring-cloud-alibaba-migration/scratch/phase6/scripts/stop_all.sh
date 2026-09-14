#!/usr/bin/env bash
# Phase 6 停止脚本：只终止 phase6/pids.txt 里记录的、本次演练启动的 JVM；随后停 compose 容器。
set -u
P6=/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6
BASE=/home/lenny/Projects/pps/product

echo "== 停止本次启动的 JVM（pids.txt） =="
if [ -f "$P6/pids.txt" ]; then
  while read -r name pid port; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" && echo "  stopped $name pid=$pid"
    else
      echo "  $name pid=$pid 已不在运行"
    fi
  done < "$P6/pids.txt"
  sleep 3
  # 兜底：确认端口释放
  for port in 8080 8101 8102 8103 8104 8105; do
    ss -tln | grep -q ":$port " && echo "  WARN port $port still listening" || echo "  port $port free"
  done
fi

echo "== 停 compose 演练容器（nacos/rabbitmq/jaeger；不动 mysql/redis 既有实例） =="
cd "$BASE" && docker compose -f compose.dev.yml stop nacos rabbitmq jaeger
echo "stop_all done"
