#!/usr/bin/env bash
set -euo pipefail

# Smart build-and-run script for Forge.
# Usage: ./run.sh [--clean] [--build-only] [--run-only] [--gui] [app args...]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MARKER_FILE="$SCRIPT_DIR/.last_build_timestamp"
JAR_PATTERN="$SCRIPT_DIR/forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar"
MVN="$SCRIPT_DIR/mvnw"

JVM_ARGS=(-XX:+UseG1GC -Xms2g -Xmx2g)

# Parse flags
DO_CLEAN=false
BUILD_ONLY=false
RUN_ONLY=false
GUI_MODE=false
PASSTHROUGH_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --clean)      DO_CLEAN=true ;;
        --build-only) BUILD_ONLY=true ;;
        --run-only)   RUN_ONLY=true ;;
        --gui)        GUI_MODE=true ;;
        --help|-h)
            echo "Usage: ./run.sh [OPTIONS] [app args...]"
            echo ""
            echo "Options:"
            echo "  --clean       Force clean build (mvn clean install)"
            echo "  --build-only  Build without running"
            echo "  --run-only    Run existing JAR without building"
            echo "  --gui         Launch GUI mode instead of sim"
            echo "  -h, --help    Show this help"
            echo ""
            echo "Examples:"
            echo "  ./run.sh                          # smart build + run sim"
            echo "  ./run.sh -d deck1.dck deck2.dck   # sim with args"
            echo "  ./run.sh --gui                    # smart build + run GUI"
            echo "  ./run.sh --clean                  # clean build + run sim"
            echo "  ./run.sh --run-only               # skip build, just run"
            exit 0
            ;;
        *)            PASSTHROUGH_ARGS+=("$arg") ;;
    esac
done

if $BUILD_ONLY && $RUN_ONLY; then
    echo "Error: --build-only and --run-only are mutually exclusive."
    exit 1
fi

find_jar() {
    local jars=( $JAR_PATTERN )
    if [[ ${#jars[@]} -eq 0 ]] || [[ ! -f "${jars[0]}" ]]; then
        return 1
    fi
    if [[ ${#jars[@]} -gt 1 ]]; then
        ls -t "${jars[@]}" 2>/dev/null | head -1
    else
        echo "${jars[0]}"
    fi
}

sources_changed() {
    if [[ ! -f "$MARKER_FILE" ]]; then
        return 0
    fi
    local changed
    changed=$(find "$SCRIPT_DIR" \
        \( -path "*/src/*.java" -o -name "pom.xml" \) \
        -newer "$MARKER_FILE" \
        -print -quit 2>/dev/null)
    [[ -n "$changed" ]]
}

do_build() {
    local mvn_args=()
    if $DO_CLEAN; then
        mvn_args+=(clean)
    fi
    mvn_args+=(install -DskipTests -T 1C)

    echo "==> Building: $MVN ${mvn_args[*]}"
    "$MVN" "${mvn_args[@]}"
    touch "$MARKER_FILE"
    echo "==> Build complete."
}

do_run() {
    local jar
    jar=$(find_jar) || {
        echo "Error: No JAR found. Run without --run-only to build first, or use --clean."
        exit 1
    }

    local cmd=(java "${JVM_ARGS[@]}" -jar "$jar")
    if ! $GUI_MODE; then
        cmd+=(sim)
    fi
    if [[ ${#PASSTHROUGH_ARGS[@]} -gt 0 ]]; then
        cmd+=("${PASSTHROUGH_ARGS[@]}")
    fi

    echo "==> Running: ${cmd[*]}"
    exec "${cmd[@]}"
}

# Main
cd "$SCRIPT_DIR"

if [[ ! -x "$MVN" ]]; then
    echo "Error: Maven wrapper not found at $MVN"
    echo "Install it with: mvn wrapper:wrapper -Dmaven=3.9.6"
    exit 1
fi

if $RUN_ONLY; then
    do_run
elif $BUILD_ONLY; then
    if $DO_CLEAN || sources_changed; then
        do_build
    else
        echo "==> No source changes detected. Skipping build. (Use --clean to force)"
    fi
elif $DO_CLEAN; then
    do_build
    do_run
else
    if sources_changed; then
        do_build
    else
        echo "==> No source changes detected. Skipping build."
    fi
    do_run
fi
