#!/bin/sh
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROP_FILE="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
MAVEN_VERSION=$(sed -n 's/^mavenVersion=//p' "$PROP_FILE")
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROP_FILE")
if [ -z "$MAVEN_VERSION" ] || [ -z "$DIST_URL" ]; then
  echo "Invalid Maven wrapper properties" >&2
  exit 1
fi
MAVEN_HOME_BASE="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_BIN="$MAVEN_HOME_BASE/apache-maven-$MAVEN_VERSION/bin/mvn"
if [ ! -x "$MAVEN_BIN" ]; then
  mkdir -p "$MAVEN_HOME_BASE"
  ZIP_FILE="$MAVEN_HOME_BASE/apache-maven-$MAVEN_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$DIST_URL" -o "$ZIP_FILE"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DIST_URL" -O "$ZIP_FILE"
  else
    echo "curl or wget is required to bootstrap Maven" >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip is required to bootstrap Maven" >&2
    exit 1
  fi
  unzip -q -o "$ZIP_FILE" -d "$MAVEN_HOME_BASE"
fi
exec "$MAVEN_BIN" "$@"
