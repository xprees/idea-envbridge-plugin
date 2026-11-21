#!/usr/bin/env bash
# Starts throwaway Vault, OpenBao, Infisical and LocalStack (AWS Secrets Manager) servers in Docker for the integration
# tests, then seeds them
# (scripts/integration/setup.sh). Stop and remove them with scripts/integration/stop.sh.
#
#   scripts/integration/start.sh && ./gradlew integrationTest
set -euo pipefail
cd "$(dirname "$0")/../.."

VAULT_IMAGE=${VAULT_IMAGE:-hashicorp/vault:1.20}
OPENBAO_IMAGE=${OPENBAO_IMAGE:-openbao/openbao:2.4.1}
# Pinned so a new Infisical release can't break CI unnoticed; bump it on purpose (and run the integration tests)
INFISICAL_IMAGE=${INFISICAL_IMAGE:-infisical/infisical:v0.167.13}
# The last community release of the 4.x line; later LocalStack images need an account's auth token
LOCALSTACK_IMAGE=${LOCALSTACK_IMAGE:-localstack/localstack:4.14.0}

scripts/integration/stop.sh >/dev/null 2>&1 || true

docker run -d --name envbridge-it-vault -p 127.0.0.1:8200:8200 --cap-add=IPC_LOCK \
  -e VAULT_DEV_ROOT_TOKEN_ID=root-token "$VAULT_IMAGE" >/dev/null
docker run -d --name envbridge-it-openbao -p 127.0.0.1:8300:8200 \
  "$OPENBAO_IMAGE" server -dev -dev-root-token-id=root-token -dev-listen-address=0.0.0.0:8200 >/dev/null

docker network create envbridge-it >/dev/null
docker run -d --name envbridge-it-postgres --network envbridge-it \
  -e POSTGRES_USER=infisical -e POSTGRES_PASSWORD=infisical -e POSTGRES_DB=infisical postgres:16-alpine >/dev/null
docker run -d --name envbridge-it-redis --network envbridge-it redis:7-alpine >/dev/null
# Test-only keys for a throwaway instance
docker run -d --name envbridge-it-infisical --network envbridge-it -p 127.0.0.1:8080:8080 \
  -e ENCRYPTION_KEY=6c1fe4e407b8911c104518103505b218 \
  -e AUTH_SECRET=5lrMXKKWCVocS/uerPsl7V+TX/aaUaI7iDkgl3tSmLE= \
  -e DB_CONNECTION_URI=postgres://infisical:infisical@envbridge-it-postgres:5432/infisical \
  -e REDIS_URL=redis://envbridge-it-redis:6379 \
  -e SITE_URL=http://localhost:8080 -e TELEMETRY_ENABLED=false "$INFISICAL_IMAGE" >/dev/null

# AWS Secrets Manager only; LocalStack doesn't check signatures (the unit tests' mock does)
docker run -d --name envbridge-it-localstack -p 127.0.0.1:4566:4566 \
  -e SERVICES=secretsmanager -e EAGER_SERVICE_LOADING=1 "$LOCALSTACK_IMAGE" >/dev/null

scripts/integration/setup.sh
