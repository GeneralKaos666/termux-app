#!/usr/bin/env bash
# Sandboxed test harness for termux-backup. Never touches the real installation.
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
# shellcheck disable=SC2034 # used by sourced test files
SCRIPT="$REPO_ROOT/scripts/termux-backup/termux-backup"
ORIGINAL_PATH=$PATH
REAL_TAR=$(command -v tar)
export REAL_TAR
REAL_DATE=$(command -v date)
export REAL_DATE

setup_sandbox() {
    SANDBOX=$(mktemp -d "${TMPDIR:-/tmp}/termux-backup-test.XXXXXX")
    export SANDBOX
    mkdir -p "$SANDBOX"/{home,prefix/etc,backups,shims,config,work}
    export HOME="$SANDBOX/home" PREFIX="$SANDBOX/prefix"
    export TERMUX_BACKUP_ROOT="$SANDBOX/backups"
    export XDG_CONFIG_HOME="$SANDBOX/config"
    export PATH="$SANDBOX/shims:$ORIGINAL_PATH"
    export FIXTURE_PACKAGES=$'bash\t\tinstall\ncurl\t\tinstall\n'
    export FIXTURE_INSTALLED=$'bash\n'
    export PKG_EXIT_CODE=0
    export DPKG_QUERY_EXIT=0
    unset TAR_FAIL DATE_FIXTURE || true
    write_shims
}

teardown_sandbox() { rm -rf "$SANDBOX"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

write_shims() {
    cat > "$SANDBOX/shims/dpkg" <<'EOF'
#!/usr/bin/env bash
printf '%s' "$FIXTURE_PACKAGES"
EOF
    cat > "$SANDBOX/shims/dpkg-query" <<'EOF'
#!/usr/bin/env bash
[ "${DPKG_QUERY_EXIT:-0}" = 0 ] || {
    echo "dpkg-query: unable to access the dpkg database" >&2
    exit "${DPKG_QUERY_EXIT}"
}
printf '%s' "$FIXTURE_INSTALLED"
EOF
    cat > "$SANDBOX/shims/date" <<'EOF'
#!/usr/bin/env bash
if [ -n "${DATE_FIXTURE:-}" ]; then
    for arg in "$@"; do
        if [ "$arg" = "+%Y%m%d_%H%M%S" ]; then
            printf '%s\n' "$DATE_FIXTURE"
            exit 0
        fi
    done
fi
exec "$REAL_DATE" "$@"
EOF
    cat > "$SANDBOX/shims/pkg" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SANDBOX/pkg.log"
exit "${PKG_EXIT_CODE:-0}"
EOF
    cat > "$SANDBOX/shims/tar" <<'EOF'
#!/usr/bin/env bash
if [ "${TAR_FAIL:-0}" = 1 ]; then
    echo "tar: simulated failure" >&2
    exit 1
fi
exec "$REAL_TAR" "$@"
EOF
    chmod 755 "$SANDBOX/shims/"*
}

assert_eq() { [ "$1" = "$2" ] || fail "$3: expected [$1], got [$2]"; }
assert_success() { ( "$@" ) >/dev/null 2>&1 || fail "expected success: $*"; }
assert_exit() { local want=$1; shift; set +e; ( "$@" ) >/dev/null 2>&1; local got=$?; set -e
    [ "$got" = "$want" ] || fail "expected exit $want, got $got: $*"; }
assert_contains() { [[ "$2" == *"$1"* ]] || fail "$3: missing [$1]"; }
assert_not_contains() { [[ "$2" != *"$1"* ]] || fail "$3: unexpected [$1]"; }
assert_file() { [ -f "$1" ] || fail "missing file: $1"; }
assert_no_file() { [ ! -e "$1" ] || fail "unexpected file: $1"; }

load_libs() {
    # shellcheck source=../lib/common.sh
    source "$SCRIPT_DIR/lib/common.sh"
    # shellcheck source=../lib/storage.sh
    source "$SCRIPT_DIR/lib/storage.sh"
    # shellcheck source=../lib/archive.sh
    source "$SCRIPT_DIR/lib/archive.sh"
    # shellcheck source=../lib/restore.sh
    source "$SCRIPT_DIR/lib/restore.sh"
    color_init
}
