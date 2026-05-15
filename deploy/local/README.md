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

Production domain for this migration:

```text
https://vlainter.rktclgh.site
```

The backend repository workflow builds the frontend bundle, packages the Spring Boot app image, pushes it to DockerHub, and then dispatches only the deploy phase to the self-hosted runner. The frontend repository workflow only sends `repository_dispatch` to the backend repository when FE `main` changes.

### GitHub Repository Secrets And Variables

Set these secrets on `rktclgh/vlainter_BE_local`:

```text
DOCKERHUB_USERNAME
DOCKERHUB_TOKEN
VITE_KAKAO_CLIENT_ID
VITE_KAKAO_AUTH_URI
VITE_KAKAO_REDIRECT_URI
VITE_API_BASE_URL
VITE_FRONTEND_BASE_URL
```

`VITE_API_BASE_URL`, `VITE_FRONTEND_BASE_URL`, `VITE_KAKAO_AUTH_URI`, and `VITE_KAKAO_REDIRECT_URI` have safe workflow defaults for `vlainter.rktclgh.site`, but setting them explicitly keeps the production build independent from workflow defaults.

Set this secret on `rktclgh/vlainter_FE_local`:

```text
CI_GITHUB_TOKEN
```

The backend workflow checks out `rktclgh/vlainter_FE_local` as a public repository and does not need a persistent PAT for that step. The FE repository only needs `CI_GITHUB_TOKEN` if FE-only `main` pushes should automatically dispatch a BE redeploy. Use a dedicated fine-grained PAT for that secret, not a long-lived personal CLI token. It must be able to create a `repository_dispatch` event in `rktclgh/vlainter_BE_local`.

Optional repository variable on `rktclgh/vlainter_BE_local`:

```text
PUBLIC_HEALTH_URL=https://vlainter.rktclgh.site/actuator/health
```

Add `PUBLIC_HEALTH_URL` after host Nginx and HTTPS are ready. When this variable is set, each deployment verifies the public HTTPS endpoint after the internal blue/green switch.

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
REDIS_HOST=host.docker.internal
REDIS_PORT=6379
REDIS_PASSWORD=<same as REDIS_PASSWORD>
REDIS_SSL_ENABLED=false
COOKIE_DOMAIN=vlainter.rktclgh.site
COOKIE_SECURE=true
CORS_ALLOWED_ORIGINS=https://vlainter.rktclgh.site
REDIRECT_ALLOWED_ORIGINS=https://vlainter.rktclgh.site,vlainter://auth/callback
KAKAO_REDIRECT_URI=https://vlainter.rktclgh.site/auth/kakao/callback
TRUSTED_PROXY_CIDRS=127.0.0.1/32,172.16.0.0/12,::1/128
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
REDIS_HOST=host.docker.internal
REDIS_PORT=6379
REDIS_PASSWORD=<same as REDIS_PASSWORD>
REDIS_SSL_ENABLED=false
AWS_S3_BUCKET=vlainter-local
AWS_REGION=ap-northeast-2
AWS_S3_ENDPOINT=http://minio:9000
AWS_S3_PATH_STYLE_ACCESS=true
```

MinIO commonly requires S3 path-style access, so local deployments should keep `AWS_S3_PATH_STYLE_ACCESS=true`.

## Hermes Generation And Gemini Embeddings

Local migration separates generation and embeddings:

```properties
AI_PROVIDER=HERMES
AI_EMBEDDING_PROVIDER=GEMINI
GEMINI_API_KEY=<your-gemini-api-key>
GEMINI_EMBEDDING_MODEL=gemini-embedding-001
GEMINI_EMBEDDING_OUTPUT_DIMENSIONALITY=768
HERMES_ENDPOINT=http://host.docker.internal:8788/generate
HERMES_PROFILE=vlainter-stateless-llm
HERMES_ENDPOINT_BIND=0.0.0.0
HERMES_ENDPOINT_PORT=8788
HERMES_CLI_PROVIDER=openai-codex
HERMES_CLI_MODEL=gpt-5.4-mini
HERMES_CLI_TIMEOUT_SECONDS=180
HERMES_READ_TIMEOUT_SECONDS=180
BEDROCK_ENABLED=false
```

Do not keep `HERMES_ENDPOINT` pointed at the old Mac test endpoint. The local server endpoint is provided by `deploy/local/hermes_oneshot_endpoint.py`, which wraps the Ubuntu server's Hermes CLI one-shot mode and exposes `POST /generate`.

Install or refresh the endpoint service after syncing `deploy/` to the server:

```bash
sudo cp /home/song/Desktop/vlainter/deploy/systemd/vlainter-hermes-endpoint.service /etc/systemd/system/vlainter-hermes-endpoint.service
sudo systemctl daemon-reload
sudo systemctl enable --now vlainter-hermes-endpoint.service
curl http://127.0.0.1:8788/health
```

The Hermes side should be exposed as a stateless one-shot endpoint for VlaInter. Use a dedicated Hermes profile such as `vlainter-stateless-llm` with memory, tools, workspace side effects, and session carryover disabled or ignored. VlaInter sends one prompt per request and expects one JSON-compatible response body.

Do not point VlaInter directly at a stateful chat/session endpoint unless that endpoint is wrapped so each call is isolated.

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
