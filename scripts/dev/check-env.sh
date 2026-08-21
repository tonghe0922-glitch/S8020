#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-${SJG_ENV_FILE:-${ROOT_DIR}/.env}}"

declare -a errors=()

add_error() {
  errors+=("$1")
}

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[check-env] environment file not found: ${ENV_FILE}" >&2
  echo "[check-env] create it with: cp .env.example .env" >&2
  exit 1
fi

# Report every unresolved placeholder before sourcing the file. Values are never printed.
while IFS=: read -r line_number assignment; do
  [[ -n "${line_number}" ]] || continue
  variable_name="$(printf '%s' "${assignment}" | sed -E 's/^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*).*/\1/')"
  add_error "${variable_name} is still an __SET_LOCAL_* placeholder (line ${line_number})"
done < <(grep -nE '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=.*__SET_LOCAL_' "${ENV_FILE}" || true)

set -a
# shellcheck disable=SC1090
if ! source "${ENV_FILE}"; then
  echo "[check-env] failed to load ${ENV_FILE}; check shell quoting and KEY=value syntax" >&2
  exit 1
fi
set +a

required_variables=(
  POSTGRES_USER
  POSTGRES_PASSWORD
  POSTGRES_DB
  POSTGRES_PORT
  SJG_BOOTSTRAP_DB_URL
  SJG_BOOTSTRAP_DB_USERNAME
  SJG_BOOTSTRAP_DB_PASSWORD
  SJG_MIGRATION_DB_USERNAME
  SJG_MIGRATION_DB_PASSWORD
  SJG_OMS_MIGRATION_DB_URL
  SJG_AUDIT_MIGRATION_DB_URL
  SJG_DW_MIGRATION_DB_URL
  SJG_API_DB_URL
  SJG_API_DB_USERNAME
  SJG_API_DB_PASSWORD
  SJG_WORKER_DB_URL
  SJG_WORKER_DB_USERNAME
  SJG_WORKER_DB_PASSWORD
  SJG_AUDIT_WRITER_DB_PASSWORD
  SJG_AUDITOR_DB_PASSWORD
  SJG_DW_WRITER_DB_PASSWORD
  SJG_DW_READER_DB_PASSWORD
  SJG_AUDIT_DB_URL
  SJG_AUDIT_DB_USERNAME
  SJG_AUDIT_DB_PASSWORD
  SJG_SECURITY_AUDIT_MODE
  REDIS_HOST
  REDIS_PORT
  SJG_TENANT_ID
  SJG_TENANT_CODE
  SJG_TENANT_NAME
  SJG_ADMIN_LOGIN_NAME
  SJG_ADMIN_PASSWORD_HASH
  SJG_ADMIN_EMPLOYEE_NO
  MINIO_ROOT_USER
  MINIO_ROOT_PASSWORD
  RABBITMQ_DEFAULT_USER
  RABBITMQ_DEFAULT_PASS
)

for variable_name in "${required_variables[@]}"; do
  value="${!variable_name-}"
  if [[ -z "${value//[[:space:]]/}" ]]; then
    add_error "${variable_name} is missing or empty"
  fi
done

uuid_pattern='^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$'
login_pattern='^[A-Za-z0-9._@-]{1,128}$'
employee_pattern='^[A-Za-z0-9._-]{1,64}$'
bcrypt_pattern='^\$2[aby]\$12\$[./A-Za-z0-9]{53}$'
tenant_code_pattern='^[A-Za-z0-9._-]{1,64}$'

[[ "${SJG_TENANT_ID-}" =~ ${uuid_pattern} ]] || add_error "SJG_TENANT_ID must be a standard UUID"
[[ "${SJG_TENANT_CODE-}" =~ ${tenant_code_pattern} ]] || add_error "SJG_TENANT_CODE may contain only letters, digits, dot, underscore and hyphen"
[[ "${SJG_ADMIN_LOGIN_NAME-}" =~ ${login_pattern} ]] || add_error "SJG_ADMIN_LOGIN_NAME has an invalid format"
[[ "${SJG_ADMIN_EMPLOYEE_NO-}" =~ ${employee_pattern} ]] || add_error "SJG_ADMIN_EMPLOYEE_NO has an invalid format"
[[ "${SJG_ADMIN_PASSWORD_HASH-}" =~ ${bcrypt_pattern} ]] || add_error "SJG_ADMIN_PASSWORD_HASH must be a BCrypt cost=12 hash, not plaintext"

if [[ "${SJG_MIGRATION_DB_USERNAME-}" != "sjg_migration" ]]; then
  add_error "SJG_MIGRATION_DB_USERNAME must be sjg_migration"
fi
if [[ "${SJG_BOOTSTRAP_DB_USERNAME-}" == "sjg_migration" ]]; then
  add_error "bootstrap and migration database identities must be different"
fi
if [[ "${SJG_API_DB_USERNAME-}" != "sjg_api_runtime" ]]; then
  add_error "SJG_API_DB_USERNAME must be sjg_api_runtime"
fi
if [[ "${SJG_WORKER_DB_USERNAME-}" != "sjg_worker_runtime" ]]; then
  add_error "SJG_WORKER_DB_USERNAME must be sjg_worker_runtime"
fi
if [[ "${SJG_AUDIT_DB_USERNAME-}" != "sjg_audit_writer" ]]; then
  add_error "SJG_AUDIT_DB_USERNAME must be sjg_audit_writer"
fi
if [[ -n "${POSTGRES_PASSWORD-}" && -n "${SJG_BOOTSTRAP_DB_PASSWORD-}" \
      && "${POSTGRES_PASSWORD}" != "${SJG_BOOTSTRAP_DB_PASSWORD}" ]]; then
  add_error "SJG_BOOTSTRAP_DB_PASSWORD must match POSTGRES_PASSWORD for the local container"
fi
if [[ -n "${SJG_AUDIT_WRITER_DB_PASSWORD-}" && -n "${SJG_AUDIT_DB_PASSWORD-}" \
      && "${SJG_AUDIT_WRITER_DB_PASSWORD}" != "${SJG_AUDIT_DB_PASSWORD}" ]]; then
  add_error "SJG_AUDIT_DB_PASSWORD must match SJG_AUDIT_WRITER_DB_PASSWORD"
fi

case "${SJG_SECURITY_AUDIT_MODE-}" in
  fail-open|fail-closed) ;;
  *) add_error "SJG_SECURITY_AUDIT_MODE must be fail-open or fail-closed" ;;
esac

jdbc_variables=(
  SJG_BOOTSTRAP_DB_URL
  SJG_OMS_MIGRATION_DB_URL
  SJG_AUDIT_MIGRATION_DB_URL
  SJG_DW_MIGRATION_DB_URL
  SJG_API_DB_URL
  SJG_WORKER_DB_URL
  SJG_AUDIT_DB_URL
)
for variable_name in "${jdbc_variables[@]}"; do
  value="${!variable_name-}"
  [[ "${value}" == jdbc:postgresql://* ]] || add_error "${variable_name} must be a PostgreSQL JDBC URL"
done

port_variables=(
  POSTGRES_PORT
  REDIS_PORT
  MINIO_API_PORT
  MINIO_CONSOLE_PORT
  RABBITMQ_AMQP_PORT
  RABBITMQ_MANAGEMENT_PORT
  API_PORT
  WORK_WEB_PORT
  TECH_WEB_PORT
)
for variable_name in "${port_variables[@]}"; do
  value="${!variable_name-}"
  if [[ -n "${value}" ]] && { [[ ! "${value}" =~ ^[0-9]+$ ]] || (( value < 1 || value > 65535 )); }; then
    add_error "${variable_name} must be an integer between 1 and 65535"
  fi
done

if ((${#errors[@]} > 0)); then
  echo "[check-env] ${#errors[@]} environment problem(s) found in ${ENV_FILE}:" >&2
  for error in "${errors[@]}"; do
    echo "  - ${error}" >&2
  done
  echo "[check-env] fix the listed items, then run this command again." >&2
  exit 1
fi

echo "[check-env] PASS: required deployment, runtime and first-admin variables are valid."
