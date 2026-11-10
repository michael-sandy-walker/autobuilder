#!/bin/bash
# prepare_t4en_compose_case.sh -- T4-EN toy case v1: a project that names its own service in a compose file.
# A copy of the operator's e-commerce-project (its code only) with a docker-compose.yml naming a DIFFERENT image than
# its README does (MariaDB, where the README says MySQL 8), so a run shows which one was started: the compose file's
# (taken as written) or the model's reading of the README. The app service is listed with `build:` and must be skipped.
# Usage: scripts/prepare_t4en_compose_case.sh [case-name]   (default e-commerce-compose)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CASE="${1:-e-commerce-compose}"
SRC="$ROOT/data/e-commerce-project/output"
DEST="$ROOT/data/$CASE"
[ -f "$SRC/pom.xml" ] || { echo "refusing: $SRC holds no project" >&2; exit 1; }
[ -e "$DEST" ] && { echo "refusing: $DEST exists" >&2; exit 1; }
mkdir -p "$DEST/output"
( cd "$SRC" && tar --exclude=./target -cf - . ) | ( cd "$DEST/output" && tar -xf - )
cat > "$DEST/output/docker-compose.yml" <<'EOF'
# T4-EN toy case v1
services:
  app:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - db
  db:
    image: mariadb:11.4
    ports:
      - "3306:3306"
    environment:
      MARIADB_ALLOW_EMPTY_ROOT_PASSWORD: "yes"
      MARIADB_DATABASE: ecommjava
EOF
echo "laid out $CASE (v1) in $DEST"
