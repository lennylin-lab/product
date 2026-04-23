#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT_DIR/scripts/dev-env.sh"

MYSQL_CONTAINER="product-mysql"
SCHEMA_FILE="$ROOT_DIR/schema.sql"
MAX_RETRIES=60
SLEEP_SECONDS=2

if [[ ! -f "$SCHEMA_FILE" ]]; then
    echo "缺少 DDL 文件：$SCHEMA_FILE" >&2
    exit 1
fi

echo "等待 MySQL 容器就绪：$MYSQL_CONTAINER"
for ((i = 1; i <= MAX_RETRIES; i++)); do
    if docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$MYSQL_CONTAINER" 2>/dev/null || true)"
        if [[ "$status" == "healthy" || "$status" == "running" ]]; then
            break
        fi
    fi

    if [[ $i -eq MAX_RETRIES ]]; then
        echo "MySQL 容器未在预期时间内就绪：$MYSQL_CONTAINER" >&2
        exit 1
    fi
    sleep "$SLEEP_SECONDS"
done

table_count="$(docker exec "$MYSQL_CONTAINER" mysql \
    -N -s \
    -uroot \
    "-p${MYSQL_ROOT_PASSWORD}" \
    -D "$MYSQL_DATABASE" \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${MYSQL_DATABASE}';")"

if [[ "${table_count:-0}" -gt 0 ]]; then
    echo "数据库 ${MYSQL_DATABASE} 已存在 ${table_count} 张表，跳过 DDL 初始化。"
    exit 0
fi

echo "数据库 ${MYSQL_DATABASE} 为空，开始导入 schema.sql"
docker exec -i "$MYSQL_CONTAINER" mysql \
    -uroot \
    "-p${MYSQL_ROOT_PASSWORD}" \
    "$MYSQL_DATABASE" < "$SCHEMA_FILE"

echo "数据库 ${MYSQL_DATABASE} 初始化完成。"
