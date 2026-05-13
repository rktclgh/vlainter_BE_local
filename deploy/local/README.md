# VlaInter Local Infrastructure

This folder contains the first local Ubuntu infrastructure layer for the migration.

PostgreSQL and Redis are installed directly on the Ubuntu host so multiple projects can share them without running one database container per project.

This folder starts only project-local container services:

- MinIO
- MinIO bucket bootstrap job

It does not start the Spring Boot app yet. The app boot requires the RDS schema baseline because production-like config uses `JPA_DDL_AUTO=validate`.

Host PostgreSQL installs the `vector` extension through `install-host-postgres-redis.sh`.

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

## Host PostgreSQL And Redis

Install host-level PostgreSQL 17, pgvector, and Redis once per server:

```bash
cd /home/song/Desktop/vlainter/deploy/local
sudo ./install-host-postgres-redis.sh
```

The script reads `/home/song/Desktop/vlainter/deploy/local/.env`, installs these packages, creates the configured database/user, enables pgvector, and configures Redis with `REDIS_PASSWORD`.

PostgreSQL 17 is intentional because the current RDS source is PostgreSQL 17.6. The install script adds the PostgreSQL Global Development Group apt repository on Ubuntu 24.04 because Ubuntu's default repository only provides PostgreSQL 16.

The shared PostgreSQL instance can host many projects, but VlaInter keeps its own database:

```properties
POSTGRES_DB=vlainter
POSTGRES_USER=vlainter
```

After a custom-format RDS dump is copied to the server, restore it with:

```bash
/home/song/Desktop/vlainter/deploy/local/restore-rds-dump.sh /home/song/Desktop/vlainter/backups/rds/<dump-file>.dump
```

App containers should connect to host services through Docker's host gateway:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/vlainter
SPRING_DATASOURCE_USERNAME=vlainter
SPRING_DATASOURCE_PASSWORD=<same as POSTGRES_PASSWORD>
UPSTASH_REDIS_URL=redis://:<same as REDIS_PASSWORD>@host.docker.internal:6379
```

## First Run

From `/home/song/Desktop/vlainter/deploy/local`:

```bash
cp .env.example .env
vi .env
docker compose --env-file .env -f docker-compose.infra.yml up -d
docker compose --env-file .env -f docker-compose.infra.yml ps
```

The host ports are bound to `127.0.0.1` only:

- MinIO API: `127.0.0.1:19000`
- MinIO console: `127.0.0.1:19001`

## App Environment Mapping

When the app container is added later, use the host gateway for shared PostgreSQL/Redis and the internal Docker network name for MinIO:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/vlainter
SPRING_DATASOURCE_USERNAME=vlainter
SPRING_DATASOURCE_PASSWORD=<same as POSTGRES_PASSWORD>
UPSTASH_REDIS_URL=redis://:<same as REDIS_PASSWORD>@host.docker.internal:6379
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
docker volume rm vlainter-minio-data
```
