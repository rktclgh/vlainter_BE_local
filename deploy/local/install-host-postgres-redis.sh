#!/usr/bin/env bash

set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/home/song/Desktop/vlainter}"
LOCAL_ENV_FILE="${LOCAL_ENV_FILE:-${DEPLOY_DIR}/deploy/local/.env}"
PG_VERSION="${PG_VERSION:-17}"

if [ "$(id -u)" -ne 0 ]; then
  echo "[ERROR] Run with sudo: sudo DEPLOY_DIR=${DEPLOY_DIR} $0"
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

if ! printf '%s' "$POSTGRES_DB" | grep -Eq '^[A-Za-z0-9_]+$'; then
  echo "[ERROR] POSTGRES_DB must contain only letters, numbers, and underscore"
  exit 1
fi

if ! printf '%s' "$POSTGRES_USER" | grep -Eq '^[A-Za-z0-9_]+$'; then
  echo "[ERROR] POSTGRES_USER must contain only letters, numbers, and underscore"
  exit 1
fi

if [ -z "${REDIS_PASSWORD:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    REDIS_PASSWORD="$(openssl rand -base64 32 | tr -d '\n')"
  else
    REDIS_PASSWORD="$(date +%s%N | sha256sum | awk '{print $1}')"
  fi
  printf '\nREDIS_PASSWORD=%s\n' "$REDIS_PASSWORD" >> "$LOCAL_ENV_FILE"
  echo "[INFO] REDIS_PASSWORD was missing and has been appended to $LOCAL_ENV_FILE"
fi

apt-get update
apt-get install -y ca-certificates curl gnupg lsb-release

install -d /usr/share/postgresql-common/pgdg
if [ ! -f /usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg ]; then
  curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    | gpg --dearmor -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg
fi

CODENAME="$(. /etc/os-release && printf '%s' "$VERSION_CODENAME")"
cat > /etc/apt/sources.list.d/pgdg.list <<EOF
deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg] https://apt.postgresql.org/pub/repos/apt ${CODENAME}-pgdg main
EOF

apt-get update
apt-get install -y "postgresql-${PG_VERSION}" "postgresql-${PG_VERSION}-pgvector" redis-server

systemctl enable --now postgresql
systemctl enable --now redis-server

PG_CONF="/etc/postgresql/${PG_VERSION}/main/postgresql.conf"
PG_HBA="/etc/postgresql/${PG_VERSION}/main/pg_hba.conf"

if grep -Eq '^[#[:space:]]*listen_addresses[[:space:]]*=' "$PG_CONF"; then
  sed -i "s/^[#[:space:]]*listen_addresses[[:space:]]*=.*/listen_addresses = '*'/" "$PG_CONF"
else
  printf "\nlisten_addresses = '*'\n" >> "$PG_CONF"
fi

if ! grep -q 'vlainter docker bridge access' "$PG_HBA"; then
  cat >> "$PG_HBA" <<'EOF'

# vlainter docker bridge access
host all all 172.16.0.0/12 scram-sha-256
EOF
fi

sudo -u postgres psql --set ON_ERROR_STOP=1 \
  -v db_name="$POSTGRES_DB" \
  -v db_user="$POSTGRES_USER" \
  -v db_password="$POSTGRES_PASSWORD" <<'SQL'
SELECT CASE
  WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db_user')
    THEN format('ALTER ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_password')
  ELSE format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_password')
END
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'db_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name')
\gexec
SQL

sudo -u postgres psql --set ON_ERROR_STOP=1 -d "$POSTGRES_DB" -c 'CREATE EXTENSION IF NOT EXISTS vector;'

REDIS_CONF="/etc/redis/redis.conf"
sed -i 's/^bind .*/bind 0.0.0.0 -::1/' "$REDIS_CONF"
sed -i 's/^# *requirepass .*/requirepass __VLAINTER_REDIS_PASSWORD__/' "$REDIS_CONF"
if grep -q '^requirepass ' "$REDIS_CONF"; then
  sed -i "s|^requirepass .*|requirepass ${REDIS_PASSWORD}|" "$REDIS_CONF"
else
  printf "\nrequirepass %s\n" "$REDIS_PASSWORD" >> "$REDIS_CONF"
fi
if grep -Eq '^appendonly ' "$REDIS_CONF"; then
  sed -i 's/^appendonly .*/appendonly yes/' "$REDIS_CONF"
else
  printf "\nappendonly yes\n" >> "$REDIS_CONF"
fi

systemctl restart postgresql
systemctl restart redis-server

sudo -u postgres psql -d "$POSTGRES_DB" -Atc "SELECT extname FROM pg_extension WHERE extname = 'vector';"
redis-cli -a "$REDIS_PASSWORD" ping

echo "[INFO] Host PostgreSQL ${PG_VERSION}, pgvector, and Redis are ready."
echo "[INFO] App containers should use host.docker.internal:5432 and host.docker.internal:6379."
