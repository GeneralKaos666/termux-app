#!/usr/bin/env bash
# Runs every test_* function defined by the *_test.sh files.
set -uo pipefail

TESTS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=tests/harness.sh
source "$TESTS_DIR/harness.sh"
export SCRIPT_DIR="$REPO_ROOT/scripts/termux-backup"

for test_file in "$TESTS_DIR"/*_test.sh; do
    [ -e "$test_file" ] || continue
    # shellcheck disable=SC1090
    source "$test_file"
done

count=0
failed=0
while IFS= read -r name; do
    [ -n "$name" ] || continue
    count=$((count + 1))
    if ( set -euo pipefail; "$name" ) >/dev/null 2>&1; then
        printf '%s: PASS\n' "$name"
    else
        printf '%s: FAIL\n' "$name"
        failed=$((failed + 1))
    fi
done < <(declare -F | awk '{print $3}' | grep '^test_' || true)

if [ "$failed" -ne 0 ]; then
    printf '%s cases, %s failed\n' "$count" "$failed"
    exit 1
fi
printf '%s passed\n' "$count"
