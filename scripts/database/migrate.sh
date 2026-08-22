#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
cd "$ROOT"

if [ -n "${SJG_ENV_FILE:-}" ]; then
  ENV_FILE=$SJG_ENV_FILE
elif [ -f "$ROOT/.env" ]; then
  ENV_FILE=$ROOT/.env
else
  ENV_FILE=
fi

if [ -n "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

: "${SJG_BOOTSTRAP_DB_URL:=jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/postgres}"
: "${SJG_BOOTSTRAP_DB_USERNAME:=${POSTGRES_USER:-}}"
: "${SJG_BOOTSTRAP_DB_PASSWORD:=${POSTGRES_PASSWORD:-}}"
export SJG_BOOTSTRAP_DB_URL SJG_BOOTSTRAP_DB_USERNAME SJG_BOOTSTRAP_DB_PASSWORD

# Use the module POM directly so exec:java runs with the database-baseline
# output directory on the runtime classpath instead of the root aggregator.
bash ./mvnw -q -ntp \
  -f technical-platform/backend/modules/database-baseline/pom.xml \
  -DskipTests compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=cn.shangjingu.platform.database.Phase03DatabaseMigrator \
  -Dexec.classpathScope=runtime
