# VlaInter Local Infrastructure

This folder contains the first local Ubuntu infrastructure layer for the migration.

It starts only infrastructure services:

- PostgreSQL 16 with pgvector
- Redis
- MinIO
- MinIO bucket bootstrap job

It does not start the Spring Boot app yet. The app boot requires the RDS schema baseline because production-like config uses `JPA_DDL_AUTO=validate`.

Fresh PostgreSQL volumes install the `vector` extension through `postgres-init/001-create-vector.sql`.

## Server Path

Use this path on the new Ubuntu server:

```bash
/home/song/Desktop/vlainter
```

The local layout should mirror the current EC2 layout:

```bash
/home/ubuntu/vlainter
```

## GitHub Actions Deployment

The deployment job uses a self-hosted GitHub Actions runner with these labels:

```text
self-hosted, linux, local-ubuntu
```

This is required because the server currently lives on the private LAN. A GitHub-hosted runner cannot reach `192.168.123.103` unless a VPN, tunnel, or public SSH route is added later.

The self-hosted runner is only the deployment executor. Build work stays on GitHub-hosted runners, which build and push the Docker image. The server runner only copies deploy assets into `/home/song/Desktop/vlainter/deploy`, pulls the pushed image, switches blue/green containers, and runs health checks through local Docker.

Do not expose SSH only for CI/CD. The runner connects outbound to GitHub and receives jobs from there.

## First Run

From `/home/song/Desktop/vlainter/deploy/local`:

```bash
cp .env.example .env
vi .env
docker compose --env-file .env -f docker-compose.infra.yml up -d
docker compose --env-file .env -f docker-compose.infra.yml ps
```

The host ports are bound to `127.0.0.1` only:

- PostgreSQL: `127.0.0.1:15432`
- Redis: `127.0.0.1:16379`
- MinIO API: `127.0.0.1:19000`
- MinIO console: `127.0.0.1:19001`

## App Environment Mapping

When the app container is added later, use the internal Docker network names:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/vlainter
SPRING_DATASOURCE_USERNAME=vlainter
SPRING_DATASOURCE_PASSWORD=<same as POSTGRES_PASSWORD>
UPSTASH_REDIS_URL=redis://redis:6379
AWS_S3_BUCKET=vlainter-local
AWS_REGION=ap-northeast-2
AWS_S3_ENDPOINT=http://minio:9000
```

MinIO commonly requires S3 path-style access. If uploads fail after the app is attached, add a backend config flag for path-style access in `S3Config`.

The MinIO images are intentionally `latest` during the first smoke-test phase to avoid pinning an unverified tag. Pin tested digests or release tags before treating this as a production deployment file.

## Safe Reset

Stopping containers keeps volumes:

```bash
docker compose --env-file .env -f docker-compose.infra.yml down
```

Deleting data is destructive and should be done only when intentionally resetting the local environment:

```bash
docker volume rm vlainter-postgres-data vlainter-redis-data vlainter-minio-data
```
