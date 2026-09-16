#!/usr/bin/env bash
# =============================================================================
# it-down.sh — 停止 it-up.sh 启动的 IT 服务实例
#
# 进程安全：只杀 $BASE/scratch/integration-tests/pids.txt 中记录的 PID
#（逐个校验命令行匹配再 kill，防止 PID 复用误伤）；infra 容器默认不动
#（那是用户可能常驻的共享栈），IT_STOP_INFRA=1 时才额外收掉 compose 四件套。
# =============================================================================
set -euo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PIDS="$BASE/scratch/integration-tests/pids.txt"

if [ ! -f "$PIDS" ]; then
  echo "无 pids 记录（$PIDS），无需清理"
else
  while read -r name pid port; do
    [ -n "${pid:-}" ] || continue
    if [ -d "/proc/$pid" ]; then
      cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || echo '')"
      case "$cmd" in
        *product-*0.0.1-SNAPSHOT.jar*)
          kill "$pid" 2>/dev/null || true
          echo "stopped $name pid=$pid port=$port"
          ;;
        *)
          echo "SKIP $name pid=$pid（命令行不匹配 IT 服务 jar，疑似 PID 复用）"
          ;;
      esac
    else
      echo "already-gone $name pid=$pid"
    fi
  done < "$PIDS"
  : > "$PIDS"
fi

if [ "${IT_STOP_INFRA:-0}" = "1" ]; then
  docker compose -f "$BASE/compose.dev.yml" stop mysql redis nacos rabbitmq
  echo "infra 四件套已停止（IT_STOP_INFRA=1）"
else
  echo "infra 容器保持运行（默认不触碰；IT_STOP_INFRA=1 可停）"
fi
