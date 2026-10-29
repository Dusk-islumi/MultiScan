#!/usr/bin/env bash
# 本地启动服务端（默认只监听回环，避免误暴露）
set -euo pipefail
cd "$(dirname "$0")"

PY="${PY:-../.venv/bin/python}"
export DB_PATH="${DB_PATH:-./data/scan.db}"
export CREDENTIALS_PATH="${CREDENTIALS_PATH:-./data/credentials.txt}"

# 注册口令：绝不使用固定的默认值。未提供时自动生成一个强随机口令并落盘，
# 这样开发者本机也不需要记口令，更不会把 change-me 带到线上。
if [[ -z "${ENROLL_SECRET:-}" ]]; then
  SECRET_FILE="$(dirname "$DB_PATH")/enroll_secret.txt"
  mkdir -p "$(dirname "$SECRET_FILE")"
  chmod 700 "$(dirname "$SECRET_FILE")" 2>/dev/null || true
  if [[ ! -s "$SECRET_FILE" ]]; then
    (umask 077; "$PY" -c 'import secrets; print(secrets.token_urlsafe(32))' > "$SECRET_FILE")
    echo "[run.sh] 已生成注册口令：$SECRET_FILE" >&2
  fi
  ENROLL_SECRET="$(head -n1 "$SECRET_FILE")"
  export ENROLL_SECRET
fi

exec "$PY" -m uvicorn app.main:app --host "${HOST:-127.0.0.1}" --port "${PORT:-18080}"
