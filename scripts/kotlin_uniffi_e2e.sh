#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JNA_JAR="${JNA_JAR:-/usr/share/java/jna.jar}"
E2E_DIR="target/uniffi/kotlin-e2e"
E2E_JAR="$E2E_DIR/e2e.jar"
MAIN_SRC="test/kotlin-e2e/KotlinUniffiE2e.kt"

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: required command '$1' not found"
        exit 1
    }
}

ensure_regtest_available() {
    local compose="docker compose"
    $compose ps >/dev/null 2>&1 || {
        echo "ERROR: could not query docker compose state"
        exit 1
    }

    local service
    for service in bitcoind electrs proxy; do
        $compose ps --services --status running | grep -qx "$service" || {
            echo "ERROR: regtest service '$service' is not running; start it with './regtest.sh start'"
            exit 1
        }
    done
}

need_cmd cargo
need_cmd java
need_cmd kotlinc
need_cmd docker

[ -f "$JNA_JAR" ] || {
    echo "ERROR: JNA jar not found at '$JNA_JAR' (override with JNA_JAR=/path/to/jna.jar)"
    exit 1
}

ensure_regtest_available

cargo build --release --features uniffi --lib
./scripts/ci/uniffi_generate_kotlin.sh

mkdir -p "$E2E_DIR"

mapfile -t GENERATED_SOURCES < <(find target/uniffi/kotlin -type f -name '*.kt' | sort)
[ "${#GENERATED_SOURCES[@]}" -gt 0 ] || {
    echo "ERROR: no generated Kotlin sources found under target/uniffi/kotlin"
    exit 1
}

kotlinc \
  -cp "$JNA_JAR" \
  "${GENERATED_SOURCES[@]}" \
  "$MAIN_SRC" \
  -include-runtime \
  -d "$E2E_JAR"

LD_LIBRARY_PATH="$ROOT_DIR/target/release:${LD_LIBRARY_PATH:-}" \
java \
  -Djna.library.path="$ROOT_DIR/target/release" \
  -cp "$E2E_JAR:$JNA_JAR" \
  KotlinUniffiE2eKt
