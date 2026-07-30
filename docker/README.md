# Docker deployment

Docker assets are split by deployment responsibility:

- `infra/` runs Elasticsearch, Redis, and MySQL.
- `app/` builds and runs the Anchr App API.

The two stacks use separate environment files so they do not need to share every
secret.

## 1. Prepare Elasticsearch

Download the `analysis-ik` archive compatible with Elasticsearch `8.18.8` and
save it as:

```text
docker/infra/elasticsearch-analysis-ik-8.18.8.zip
```

The archive is excluded from Git but included in the Docker build context.

## 2. Start infrastructure

```bash
cp docker/infra/.env.example docker/infra/.env
# Replace every change-me value.
docker compose --env-file docker/infra/.env \
  -f docker/infra/compose.yml up -d --build
```

Elasticsearch, Redis, and MySQL are published on loopback-only host ports.

## 3. Start Anchr App

```bash
cp docker/app/.env.example docker/app/.env
# Match the Redis, Elasticsearch, and MySQL credentials configured above.
# Configure the Docling URL/token and generate unique application secrets.
docker compose --env-file docker/app/.env \
  -f docker/app/compose.yml up -d --build
```

The example publishes the API at `http://127.0.0.1:8081`. The application and
infrastructure stacks communicate through the shared `anchr-backend` Docker
network, so start the infrastructure stack first.

Generate application encryption material with:

```bash
openssl rand -base64 32
openssl rand -base64 16
```

Use the first value as `APP_ENCRYPT_KEY` and the second as `APP_ENCRYPT_IV`.
Never commit either `.env` file.

## Commands

```bash
docker compose --env-file docker/infra/.env -f docker/infra/compose.yml ps
docker compose --env-file docker/app/.env -f docker/app/compose.yml logs -f backend
docker compose --env-file docker/app/.env -f docker/app/compose.yml down
docker compose --env-file docker/infra/.env -f docker/infra/compose.yml down
```
