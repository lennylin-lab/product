#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_SCHEMA="$ROOT_DIR/schema.sql"
TARGET_SCHEMA="$ROOT_DIR/deploy/mysql/init/010-schema.sql"

if [[ ! -f "$SOURCE_SCHEMA" ]]; then
    echo "缺少 DDL 文件：$SOURCE_SCHEMA" >&2
    exit 1
fi

mkdir -p "$(dirname "$TARGET_SCHEMA")"
cp "$SOURCE_SCHEMA" "$TARGET_SCHEMA"
echo "已同步数据库 DDL 到 $TARGET_SCHEMA"
