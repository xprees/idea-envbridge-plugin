#!/usr/bin/env bash
# Seeds fresh Vault, OpenBao, Infisical and LocalStack servers with the data the integration tests read, and writes the connection
# details to an env file (default build/integration.env) that `./gradlew integrationTest` passes to the tests.
#
# The servers are started by scripts/integration/start.sh locally, or by the CI workflow's service containers. Every server is
# optional: one whose address variable is empty is skipped, and so are its tests.
#
#   VAULT_ADDR_IT     Vault dev server, root token VAULT_TOKEN_IT      (default http://127.0.0.1:8200, root-token)
#   OPENBAO_ADDR_IT   OpenBao dev server, root token OPENBAO_TOKEN_IT  (default http://127.0.0.1:8300, root-token)
#   INFISICAL_URL_IT  a fresh, not yet bootstrapped Infisical          (default http://127.0.0.1:8080)
#   LOCALSTACK_URL_IT LocalStack with Secrets Manager                   (default http://127.0.0.1:4566)
#
# Only throwaway test credentials are involved; nothing here is a real secret.
set -euo pipefail

OUT=${1:-build/integration.env}
VAULT_ADDR_IT=${VAULT_ADDR_IT-http://127.0.0.1:8200}
VAULT_TOKEN_IT=${VAULT_TOKEN_IT:-root-token}
OPENBAO_ADDR_IT=${OPENBAO_ADDR_IT-http://127.0.0.1:8300}
OPENBAO_TOKEN_IT=${OPENBAO_TOKEN_IT:-root-token}
INFISICAL_URL_IT=${INFISICAL_URL_IT-http://127.0.0.1:8080}
LOCALSTACK_URL_IT=${LOCALSTACK_URL_IT-http://127.0.0.1:4566}

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

wait_for() { # url, name
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null "$1"; then return 0; fi
    sleep 2
  done
  echo "$2 did not come up at $1" >&2
  return 1
}

# ---- Vault and OpenBao (same API) ------------------------------------------------------------------------------------

# Calls the Vault API: method, path, [json body], [namespace]
vapi() {
  local method=$1 path=$2 body=${3:-} ns=${4:-}
  local args=(-fsS -X "$method" -H "X-Vault-Token: $TOKEN" "$ADDR/v1/$path")
  [[ -n $ns ]] && args+=(-H "X-Vault-Namespace: $ns")
  [[ -n $body ]] && args+=(-H "Content-Type: application/json" -d "$body")
  curl "${args[@]}"
}

seed_vault() { # addr, token, prefix of the env variables, namespaces supported (true/false)
  ADDR=$1 TOKEN=$2
  local prefix=$3 namespaces=$4
  wait_for "$ADDR/v1/sys/health" "$prefix"

  # Secrets the tests read (the dev server mounts KV v2 at secret/)
  vapi POST secret/data/envbridge/app/db '{"data":{"password":"db-pass-'"$prefix"'","user":"app"}}' >/dev/null
  vapi POST secret/data/envbridge/app/api '{"data":{"key":"api-key-'"$prefix"'","limits":{"rps":10}}}' >/dev/null
  vapi POST secret/data/envbridge/app '{"data":{"name":"the app"}}' >/dev/null
  vapi POST secret/data/forbidden/x '{"data":{"hidden":"no"}}' >/dev/null
  # A second KV v2 mount
  vapi POST sys/mounts/team-kv '{"type":"kv","options":{"version":"2"}}' >/dev/null || true
  vapi POST team-kv/data/svc '{"data":{"token":"team-kv-token"}}' >/dev/null
  # A KV v1 mount (no data/ or metadata/ in its paths, the secret's fields are the body)
  vapi POST sys/mounts/legacy '{"type":"kv","options":{"version":"1"}}' >/dev/null || true
  vapi POST legacy/envbridge/app/db '{"password":"v1-db-pass-'"$prefix"'","user":"app"}' >/dev/null
  vapi POST legacy/envbridge/app/api '{"key":"v1-api-key-'"$prefix"'","limits":{"rps":5}}' >/dev/null
  vapi POST legacy/forbidden/x '{"hidden":"no"}' >/dev/null

  local policy='path "secret/data/envbridge/*" { capabilities = ["read"] }
path "secret/data/envbridge" { capabilities = ["read"] }
path "secret/metadata/envbridge/*" { capabilities = ["list", "read"] }
path "secret/metadata/envbridge" { capabilities = ["list", "read"] }
path "secret/metadata/" { capabilities = ["list"] }
path "team-kv/data/*" { capabilities = ["read"] }
path "team-kv/metadata/*" { capabilities = ["list"] }
path "team-a/secret/data/*" { capabilities = ["read"] }
path "team-a/secret/metadata/*" { capabilities = ["list"] }
path "secret/data/envbridge-rw/*" { capabilities = ["create", "read", "update", "delete"] }
path "secret/metadata/envbridge-rw/*" { capabilities = ["list", "read"] }
path "team-a/secret/data/envbridge-rw/*" { capabilities = ["create", "read", "update", "delete"] }
path "team-a/secret/metadata/envbridge-rw/*" { capabilities = ["list", "read"] }
path "secret/envbridge-probe" { capabilities = ["read"] }
path "legacy/envbridge/*" { capabilities = ["read", "list"] }
path "legacy/envbridge" { capabilities = ["read", "list"] }
path "legacy/envbridge-rw/*" { capabilities = ["create", "read", "update", "delete", "list"] }
path "team-a/legacy/envbridge-rw/*" { capabilities = ["create", "read", "update", "delete", "list"] }'
  vapi PUT sys/policies/acl/envbridge "$(python3 -c 'import json,sys; print(json.dumps({"policy": sys.argv[1]}))' "$policy")" >/dev/null

  # AppRole with fixed (test-only) credentials
  vapi POST sys/auth/approle '{"type":"approle"}' >/dev/null || true
  vapi POST auth/approle/role/envbridge '{"token_policies":["envbridge"],"token_ttl":"1h","token_max_ttl":"4h"}' >/dev/null
  vapi POST auth/approle/role/envbridge/role-id '{"role_id":"envbridge-role"}' >/dev/null
  vapi POST auth/approle/role/envbridge/custom-secret-id '{"secret_id":"envbridge-secret"}' >/dev/null || true

  # Userpass
  vapi POST sys/auth/userpass '{"type":"userpass"}' >/dev/null || true
  vapi POST auth/userpass/users/alice '{"password":"alice-pass","token_policies":["envbridge"]}' >/dev/null

  # A token with the policy, for the "Token" method
  local token
  token=$(vapi POST auth/token/create '{"policies":["envbridge"],"ttl":"2h","display_name":"envbridge-it"}' | json 'd["auth"]["client_token"]')

  if [[ $namespaces == true ]]; then
    vapi POST sys/namespaces/team-a '' >/dev/null || true
    vapi POST sys/mounts/secret '{"type":"kv","options":{"version":"2"}}' team-a >/dev/null || true
    sleep 1
    vapi POST secret/data/envbridge/app/db '{"data":{"password":"team-a-db-pass"}}' team-a >/dev/null
    vapi POST sys/mounts/legacy '{"type":"kv","options":{"version":"1"}}' team-a >/dev/null || true
  fi

  {
    echo "ENVBRIDGE_IT_${prefix}_ADDR=$ADDR"
    echo "ENVBRIDGE_IT_${prefix}_TOKEN=$token"
    echo "ENVBRIDGE_IT_${prefix}_NAMESPACES=$namespaces"
  } >> "$OUT"
  echo "Seeded $prefix at $ADDR"
}

[[ -n $VAULT_ADDR_IT ]] && seed_vault "$VAULT_ADDR_IT" "$VAULT_TOKEN_IT" VAULT false
# OpenBao has namespaces in its open-source edition (Vault only in Enterprise)
[[ -n $OPENBAO_ADDR_IT ]] && seed_vault "$OPENBAO_ADDR_IT" "$OPENBAO_TOKEN_IT" OPENBAO true

# ---- Infisical --------------------------------------------------------------------------------------------------------

iapi() { # method, path, [json body]
  local args=(--fail-with-body -sS -X "$1" -H "Authorization: Bearer $ITOKEN" "$INFISICAL_URL_IT$2")
  [[ -n ${3:-} ]] && args+=(-H "Content-Type: application/json" -d "$3")
  curl "${args[@]}"
}

if [[ -n $INFISICAL_URL_IT ]]; then
  wait_for "$INFISICAL_URL_IT/api/status" Infisical
  boot=$(curl -fsS -X POST "$INFISICAL_URL_IT/api/v1/admin/bootstrap" -H 'Content-Type: application/json' \
    -d '{"email":"admin@example.com","password":"Integration-Test-Pass-1","organization":"envbridge-it"}')
  ITOKEN=$(json 'd["identity"]["credentials"]["token"]' <<<"$boot")
  identity=$(json 'd["identity"]["id"]' <<<"$boot")
  org=$(json 'd["organization"]["slug"]' <<<"$boot")

  project=$(iapi POST /api/v1/projects '{"projectName":"envbridge-it","type":"secret-manager"}' | json 'd["project"]["id"]')
  iapi POST /api/v1/folders '{"workspaceId":"'"$project"'","projectId":"'"$project"'","environment":"dev","name":"backend","path":"/"}' >/dev/null
  secret() { # environment, path, key, value
    iapi POST "/api/v4/secrets/$3" '{"projectId":"'"$project"'","environment":"'"$1"'","secretPath":"'"$2"'","secretValue":"'"$4"'","type":"shared"}' >/dev/null
  }
  secret dev / DB_PASSWORD dev-db-pass
  secret dev / API_KEY dev-api-key
  secret dev /backend BACKEND_ONLY backend-value
  secret prod / DB_PASSWORD prod-db-pass

  # Universal Auth for the bootstrap identity: what the plugin's "Machine identity" method signs in with
  iapi POST "/api/v1/auth/universal-auth/identities/$identity" '{}' >/dev/null
  client_id=$(iapi GET "/api/v1/auth/universal-auth/identities/$identity" | json 'd["identityUniversalAuth"]["clientId"]')
  client_secret=$(iapi POST "/api/v1/auth/universal-auth/identities/$identity/client-secrets" '{"description":"envbridge-it"}' | json 'd["clientSecret"]')

  {
    echo "ENVBRIDGE_IT_INFISICAL_URL=$INFISICAL_URL_IT"
    echo "ENVBRIDGE_IT_INFISICAL_CLIENT_ID=$client_id"
    echo "ENVBRIDGE_IT_INFISICAL_CLIENT_SECRET=$client_secret"
    echo "ENVBRIDGE_IT_INFISICAL_PROJECT_ID=$project"
    echo "ENVBRIDGE_IT_INFISICAL_ORGANIZATION=$org"
  } >> "$OUT"
  echo "Seeded Infisical at $INFISICAL_URL_IT (project $project)"
fi

# ---- LocalStack (AWS Secrets Manager) ----------------------------------------------------------------------------------

# Calls the Secrets Manager JSON 1.1 API: action, json body. LocalStack accepts any signature; the credential scope names the
# region, and access key "test" is LocalStack's default account.
smapi() {
  curl --fail-with-body -sS -X POST "$LOCALSTACK_URL_IT/" \
    -H "Content-Type: application/x-amz-json-1.1" -H "X-Amz-Target: secretsmanager.$1" -H "X-Amz-Date: 20260101T000000Z" \
    -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/secretsmanager/aws4_request, SignedHeaders=host;x-amz-date, Signature=0000" \
    -d "$2"
}

if [[ -n $LOCALSTACK_URL_IT ]]; then
  for _ in $(seq 1 90); do
    if curl -fsS "$LOCALSTACK_URL_IT/_localstack/health" 2>/dev/null | grep -Eq '"secretsmanager": *"(available|running)"'; then break; fi
    sleep 2
  done
  # A JSON key/value secret per environment, and a plain-string secret
  smapi CreateSecret '{"Name":"envbridge-it/dev","ClientRequestToken":"00000000-0000-4000-8000-000000000001","SecretString":"{\"DB_PASSWORD\":\"dev-db-pass\",\"API_KEY\":\"dev-api-key\",\"PORT\":5432}"}' >/dev/null
  smapi CreateSecret '{"Name":"envbridge-it/prod","ClientRequestToken":"00000000-0000-4000-8000-000000000002","SecretString":"{\"DB_PASSWORD\":\"prod-db-pass\"}"}' >/dev/null
  smapi CreateSecret '{"Name":"envbridge-it-plain/TOKEN","ClientRequestToken":"00000000-0000-4000-8000-000000000003","SecretString":"plain-token"}' >/dev/null
  {
    echo "ENVBRIDGE_IT_AWS_ENDPOINT=$LOCALSTACK_URL_IT"
    echo "ENVBRIDGE_IT_AWS_REGION=us-east-1"
    echo "ENVBRIDGE_IT_AWS_ACCESS_KEY_ID=test"
    echo "ENVBRIDGE_IT_AWS_SECRET_ACCESS_KEY=test"
  } >> "$OUT"
  echo "Seeded LocalStack at $LOCALSTACK_URL_IT"
fi

echo "Wrote $OUT"
