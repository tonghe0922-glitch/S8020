#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${SJG_ENV_FILE:-${ROOT_DIR}/.env}"

"${ROOT_DIR}/scripts/dev/check-env.sh" "${ENV_FILE}"

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

echo "[api-run] loaded ${ENV_FILE}"
echo "[api-run] configured variables: SJG_API_DB_*, SJG_AUDIT_DB_*, REDIS_*, SJG_SECURITY_AUDIT_MODE"
echo "[api-run] security audit mode: ${SJG_SECURITY_AUDIT_MODE}"

cd "${ROOT_DIR}"
exec bash ./mvnw -pl technical-platform/backend/apps/api -am spring-boot:run "$@"
