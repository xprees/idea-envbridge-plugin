#!/usr/bin/env bash
# Removes the servers scripts/integration/start.sh started.
docker rm -f envbridge-it-vault envbridge-it-openbao envbridge-it-infisical envbridge-it-postgres envbridge-it-redis envbridge-it-localstack >/dev/null 2>&1
docker network rm envbridge-it >/dev/null 2>&1
exit 0
