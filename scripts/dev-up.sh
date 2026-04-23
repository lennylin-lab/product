#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT_DIR/scripts/dev-env.sh"

cd "$ROOT_DIR"
"$ROOT_DIR/scripts/dev-sync-schema.sh"
docker compose -f compose.dev.yml up -d
"$ROOT_DIR/scripts/dev-init-db.sh"
