#!/usr/bin/env bash

set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/home/song/Desktop/vlainter}"
LOCAL_ENV_FILE="${LOCAL_ENV_FILE:-${DEPLOY_DIR}/deploy/local/.env}"
DUMP_FILE="${1:-}"

if [ -z "$DUMP_FILE" ]; then
  echo "Usage: $0 /path/to/rds.dump"
  exit 1
fi

if [ ! -f "$DUMP_FILE" ]; then
  echo "[ERROR] Dump file does not exist: $DUMP_FILE"
  exit 1
fi

if [ ! -f "$LOCAL_ENV_FILE" ]; then
  echo "[ERROR] Missing env file: $LOCAL_ENV_FILE"
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$LOCAL_ENV_FILE"
set +a

: "${POSTGRES_DB:?POSTGRES_DB is required in $LOCAL_ENV_FILE}"
: "${POSTGRES_USER:?POSTGRES_USER is required in $LOCAL_ENV_FILE}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required in $LOCAL_ENV_FILE}"

export PGPASSWORD="$POSTGRES_PASSWORD"

RESTORE_LIST="$(mktemp)"
trap 'rm -f "$RESTORE_LIST"' EXIT

pg_restore --list "$DUMP_FILE" \
  | grep -v 'EXTENSION - vector' \
  | grep -v 'COMMENT - EXTENSION vector' \
  > "$RESTORE_LIST"

pg_restore \
  --host 127.0.0.1 \
  --port 5432 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --use-list "$RESTORE_LIST" \
  --clean \
  --if-exists \
  --no-owner \
  --no-acl \
  "$DUMP_FILE"

psql \
  --host 127.0.0.1 \
  --port 5432 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --tuples-only \
  --no-align \
  --command "SELECT current_database(), pg_size_pretty(pg_database_size(current_database())), (SELECT extname FROM pg_extension WHERE extname = 'vector');"

echo "[INFO] RDS dump restore completed into ${POSTGRES_DB}."
