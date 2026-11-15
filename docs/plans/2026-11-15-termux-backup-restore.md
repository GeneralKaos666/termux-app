# Termux Backup and Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a zero-dependency `termux-backup` CLI that archives packages, `$HOME` and `$PREFIX/etc` into a self-verifying backup and restores it safely on the same CPU architecture.

**Architecture:** A bash 5 dispatcher in `scripts/termux-backup/` sources five function-only libraries (`common`, `storage`, `archive`, `restore`, `report`). Backups are directories holding two tarballs, `packages.txt`, a `SHA256SUMS` file (authoritative) and an informational `manifest.json`. Tests run in throwaway sandboxes with fake `HOME`, `PREFIX` and `PATH` shims.

**Tech Stack:** Bash 5, GNU tar, gzip/bzip2/xz, coreutils `sha256sum`, `dpkg`, Termux `pkg`, plain-bash test harness, shellcheck, GitHub Actions.

**Spec:** `docs/specs/2026-11-15-termux-backup-restore-design.md`

## Global Constraints

- Bash 5 with `set -euo pipefail` in every script; `/bin/sh` compatibility is not required.
- No dependency beyond `tar`, `gzip`/`bzip2`/`xz`, `sha256sum`, `dpkg`, `pkg`, and bash builtins.
- `TOOL_VERSION=1.0.0`, `FORMAT_VERSION=1`.
- Default backup root `/sdcard/termux-backups`; default retention `keep=5`; `--keep 0` disables pruning.
- Exit codes: `0` success, `1` usage/unknown name, `2` verification or preflight failure, `3` partial (files restored, packages failed).
- `--json` writes exactly one object to stdout; all human messages go to stderr.
- Archive names are `home.tar.gz` and `usr_etc.tar.gz` for `gz`, with `.bz2`/`.xz` substituted for the other codecs.
- Library files define functions only and never execute on source.
- Commits use Conventional Commits with capitalized types: `Added(scripts): ...`, `Added(docs): ...`.
- 4-space indentation in bash files.

## Review Focus

- A backup root containing spaces (`--path "/sdcard/my backups"`) — every path stays quoted; Task 3 and Task 4 tests.
- A backup root inside `$HOME` — the root must never be archived into itself; Task 3 test.
- An interrupted backup — no `termux_backup_*` directory may survive that `list` would treat as usable; Task 3 test with a failing `tar` shim.
- Unusual file names inside `$HOME` (leading `-`, spaces) and symlinked config directories — they must round-trip as names and links, not be interpreted as flags or dereferenced; Task 3 tests.
- Non-interactive restore or delete on a pipe — must exit `1` instead of hanging; Task 4 and Task 6 tests.

---

### Task 1: CLI skeleton, logging and test harness

**Files:**
- Create: `scripts/termux-backup/termux-backup`
- Create: `scripts/termux-backup/lib/common.sh`
- Create: `scripts/termux-backup/tests/run.sh`
- Create: `scripts/termux-backup/tests/harness.sh`
- Create: `scripts/termux-backup/tests/cli_test.sh`

**Interfaces:**
- Produces: `die <code> <message...>` (prints `termux-backup: <message>` to stderr, exits), `log_info`/`log_warn`/`log_error <message...>`, `json_escape <string>` (stdout), `color_init` (sets `C_RESET C_RED C_GREEN C_YELLOW`, empty when `NO_COLOR` or non-tty), `confirm_word <word> <prompt>` (returns 0 only when stdin is a tty and the typed line equals `<word>`), `log_run <command> <exit_code>` (appends `<iso8601> command=<command> result=<ok|fail> code=<code>` to `~/.config/termux-backup/backup.log`), and `usage` in the dispatcher.
- Produces test helpers: `setup_sandbox`, `teardown_sandbox`, `assert_eq <expected> <actual> <label>`, `assert_success <cmd...>`, `assert_exit <code> <cmd...>`, `assert_contains <needle> <haystack> <label>`, `assert_file <path>`, `assert_no_file <path>`.
- Sandbox environment: `HOME=$SANDBOX/home`, `PREFIX=$SANDBOX/prefix`, `TERMUX_BACKUP_ROOT=$SANDBOX/backups`, `XDG_CONFIG_HOME=$SANDBOX/config`, `PATH="$SANDBOX/shims:$PATH"`. Shims: `dpkg` (prints `FIXTURE_PACKAGES`), `dpkg-query` (prints `FIXTURE_INSTALLED`), `pkg` (appends args to `$SANDBOX/pkg.log`, exits `PKG_EXIT_CODE`), `tar` (execs `$REAL_TAR` unless `TAR_FAIL=1`).

- [ ] **Step 1: Write the failing test**

```bash
# tests/cli_test.sh
test_unknown_subcommand_exits_1() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" frobnicate
    teardown_sandbox
}

test_json_and_quiet_conflict_exits_1() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" status --json --quiet
    teardown_sandbox
}

test_no_arguments_prints_usage() {
    setup_sandbox
    local out
    out=$(bash "$SCRIPT" 2>&1 || true)
    assert_contains "Usage: termux-backup" "$out" "usage line"
    teardown_sandbox
}
```

```bash
# tests/harness.sh (excerpt the implementer needs exactly)
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
SCRIPT="$REPO_ROOT/scripts/termux-backup/termux-backup"
ORIGINAL_PATH=$PATH
REAL_TAR=$(command -v tar)

setup_sandbox() {
    SANDBOX=$(mktemp -d "${TMPDIR:-/tmp}/termux-backup-test.XXXXXX")
    mkdir -p "$SANDBOX"/{home,prefix/etc,backups,shims,config}
    export HOME="$SANDBOX/home" PREFIX="$SANDBOX/prefix"
    export TERMUX_BACKUP_ROOT="$SANDBOX/backups"
    export XDG_CONFIG_HOME="$SANDBOX/config"
    export PATH="$SANDBOX/shims:$ORIGINAL_PATH"
    export FIXTURE_PACKAGES=$'bash\t\tinstall\ncurl\t\tinstall\n'
    export FIXTURE_INSTALLED=$'bash\n'
    export PKG_EXIT_CODE=0
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
printf '%s' "$FIXTURE_INSTALLED"
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL because `scripts/termux-backup/termux-backup` does not exist.

- [ ] **Step 3: Implement the dispatcher and `lib/common.sh`**

`termux-backup` shape: parse `--json`/`--quiet` globally, reject both, dispatch on `$1`, unknown or missing subcommand calls `usage` and exits 1. The dispatcher wraps the command call so every run appends a `log_run` line, then exits with the command's status. Each command becomes `cmd_backup`, `cmd_restore`, `cmd_list`, `cmd_verify`, `cmd_delete`, `cmd_prune`, `cmd_status`, all initially `die 1 "not implemented"`.

`common.sh` exports: `TOOL_VERSION="1.0.0"`, `FORMAT_VERSION=1`, `DEFAULT_ROOT="/sdcard/termux-backups"`, the five functions above, and `json_escape` implemented as a `sed`-free loop over `\`, `"`, and control characters using bash parameter expansion.

`run.sh` sources `harness.sh`, exports `SCRIPT_DIR="$REPO_ROOT/scripts/termux-backup"`, sources each `*_test.sh`, finds every function named `test_*` with `declare -F`, runs each in a subshell with `set -euo pipefail`, prints `<name>: PASS|FAIL`, and exits 1 if any case failed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS, `3 passed`, exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Add termux-backup CLI skeleton and test harness"
```

---

### Task 2: Storage primitives, `list` and `status`

**Files:**
- Create: `scripts/termux-backup/lib/storage.sh`
- Create: `scripts/termux-backup/lib/report.sh`
- Create: `scripts/termux-backup/tests/storage_test.sh`
- Modify: `scripts/termux-backup/termux-backup`

**Interfaces:**
- Consumes: `die`, `log_info`, `json_escape`, `DEFAULT_ROOT` from Task 1.
- Produces: `root_resolve <cli_path>` (echoes `cli_path` > `$TERMUX_BACKUP_ROOT` > config `root` > `$DEFAULT_ROOT`), `root_prepare <root>` (mkdir -p, writable check, `die 2` on failure), `dir_name_backup <label>`, `dir_name_safety`, `backup_exists <root> <name>`, `backup_size_bytes <dir>`, `backup_list <root>` (sorted names, only dirs containing `SHA256SUMS`), `packages_count <dir>`, and `cmd_list`/`cmd_status` in `report.sh`.
- JSON keys for `list`: `{"root": "...", "backups": [{"name": "...", "created_at": "...", "size": 123, "packages": 42, "kind": "backup"}]}`. `status`: `{"tool_version": "1.0.0", "root": "...", "writable": true, "storage_access": true, "backups": 2, "compression": "gz", "keep": 5, "free_space": 123}`.

- [ ] **Step 1: Write the failing test**

```bash
test_list_reports_only_directories_with_sums() {
    setup_sandbox
    mkdir -p "$SANDBOX/backups/termux_backup_20260101_000000"
    mkdir -p "$SANDBOX/backups/not_a_backup"
    touch "$SANDBOX/backups/termux_backup_20260101_000000/SHA256SUMS"
    local out
    out=$(bash "$SCRIPT" list --json)
    assert_contains '"name":"termux_backup_20260101_000000"' "$out" "listed backup"
    assert_not_contains '"not_a_backup"' "$out" "stray directory"
    teardown_sandbox
}

test_status_json_reports_root_and_version() {
    setup_sandbox
    local out
    out=$(bash "$SCRIPT" status --json)
    assert_contains '"tool_version":"1.0.0"' "$out" "version"
    assert_contains '"root":"'"$TERMUX_BACKUP_ROOT"'"' "$out" "root"
    teardown_sandbox
}
```

`assert_not_contains` is defined in the Task 1 harness; no extra helper is needed.

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL with `termux-backup: not implemented`.

- [ ] **Step 3: Implement storage and report functions**

`root_resolve` reads `~/.config/termux-backup/config.conf` when `XDG_CONFIG_HOME`/`HOME` resolve, parsing `key=value` lines with `[[ $line =~ ^([a-z_]+)=(.*)$ ]]`; never `source` it. `backup_list` matches `termux_backup_*` and `safety_pre_restore_*` with `[[ -d ]] && [[ -f $dir/SHA256SUMS ]]`, sorted by `LC_ALL=C sort`. `backup_size_bytes` sums `wc -c < "$dir"/*.tar.*`. Table rendering for non-JSON output: `printf '%-44s %-20s %10s %8s\n'`. Neither `list` nor `status` creates the root: a missing root yields an empty list and `"writable": false`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Add backup storage primitives with list and status"
```

---

### Task 3: Backup creation

**Files:**
- Create: `scripts/termux-backup/lib/archive.sh`
- Create: `scripts/termux-backup/tests/backup_test.sh`
- Modify: `scripts/termux-backup/lib/storage.sh` (manifest and sums writers)
- Modify: `scripts/termux-backup/termux-backup`

**Interfaces:**
- Consumes: `root_resolve`, `root_prepare`, `dir_name_backup` from Task 2.
- Produces: `excludes_list <secrets:0|1>` (one pattern per line, defaults from the spec, `extra_excludes` appended), `tar_flags_for <compression>` (echoes `-z`, `-j` or `-J`), `archive_name_for <kind> <compression>` (`home.tar.gz` etc.), `archive_create_home <out> <compression> <exclude_file>`, `archive_create_etc <out> <compression>`, `packages_write <dir>`, `sums_write <dir>`, `manifest_write <dir> <kind> <label> <compression>`, `human_size <bytes>`, and `cmd_backup` parsing `--label --keep --compression --exclude-secrets --no-home --no-config --path`.

- [ ] **Step 1: Write the failing tests**

```bash
test_backup_creates_complete_directory() {
    setup_sandbox
    echo hi > "$HOME/file.txt"
    bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    assert_file "$dir/manifest.json"
    assert_file "$dir/SHA256SUMS"
    assert_file "$dir/packages.txt"
    assert_file "$dir/home.tar.gz"
    assert_file "$dir/usr_etc.tar.gz"
    ( cd "$dir" && sha256sum -c --strict SHA256SUMS >/dev/null )
    teardown_sandbox
}

test_excludes_are_respected() {
    setup_sandbox
    mkdir -p "$HOME/.cache" "$HOME/keep"
    echo junk > "$HOME/.cache/junk"
    echo keep > "$HOME/keep/file"
    bash "$SCRIPT" backup
    local home_tar
    home_tar=$(find "$SANDBOX/backups" -name 'home.tar.gz')
    assert_not_contains ".cache/junk" "$(tar -tzf "$home_tar")" "excluded path"
    assert_contains "keep/file" "$(tar -tzf "$home_tar")" "kept path"
    teardown_sandbox
}

test_root_inside_home_is_not_archived() {
    setup_sandbox
    export TERMUX_BACKUP_ROOT="$HOME/backups"
    bash "$SCRIPT" backup
    local home_tar
    home_tar=$(find "$HOME/backups" -name 'home.tar.gz')
    assert_not_contains "backups/termux_backup" "$(tar -tzf "$home_tar")" "self archive"
    teardown_sandbox
}

test_root_with_spaces_works() {
    setup_sandbox
    export TERMUX_BACKUP_ROOT="$SANDBOX/my backups"
    assert_success bash "$SCRIPT" backup
    assert_file "$(find "$SANDBOX/my backups" -name manifest.json)"
    teardown_sandbox
}

test_failed_archive_leaves_no_listed_backup() {
    setup_sandbox
    TAR_FAIL=1 bash "$SCRIPT" backup
    assert_eq "" "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*')" "partial backup"
    teardown_sandbox
}

test_dash_and_space_names_and_symlink_survive() {
    setup_sandbox
    echo dash > "$HOME/-rf"
    echo spaced > "$HOME/my notes.txt"
    mkdir -p "$SANDBOX/real-termux"
    ln -s "$SANDBOX/real-termux" "$HOME/.termux"
    bash "$SCRIPT" backup
    local listing
    listing=$(tar -tzf "$(find "$SANDBOX/backups" -name home.tar.gz)")
    assert_contains "./-rf" "$listing" "leading dash"
    assert_contains "./my notes.txt" "$listing" "space in name"
    teardown_sandbox
}

test_exclude_secrets_omits_ssh() {
    setup_sandbox
    mkdir -p "$HOME/.ssh" && echo key > "$HOME/.ssh/id_ed25519"
    bash "$SCRIPT" backup --exclude-secrets
    assert_not_contains ".ssh/id_ed25519" "$(tar -tzf "$(find "$SANDBOX/backups" -name home.tar.gz)")" "secret"
    teardown_sandbox
}

test_no_home_and_no_config_skip_sources() {
    setup_sandbox
    echo hi > "$HOME/file.txt"
    echo conf > "$PREFIX/etc/termux.properties"
    bash "$SCRIPT" backup --no-home --no-config
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    assert_no_file "$dir/home.tar.gz"
    assert_no_file "$dir/usr_etc.tar.gz"
    teardown_sandbox
}

test_backup_prunes_to_keep() {
    setup_sandbox
    for ts in 20260101_000000 20260102_000000; do
        mkdir -p "$SANDBOX/backups/termux_backup_$ts"
        touch "$SANDBOX/backups/termux_backup_$ts/SHA256SUMS"
    done
    bash "$SCRIPT" backup --keep 2
    assert_eq 2 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' | wc -l)" "kept"
    assert_no_file "$SANDBOX/backups/termux_backup_20260101_000000"
    teardown_sandbox
}

test_backup_writes_a_log_line() {
    setup_sandbox
    bash "$SCRIPT" backup
    assert_contains "command=backup" "$(cat "$XDG_CONFIG_HOME/termux-backup/backup.log")" "log line"
    teardown_sandbox
}

test_label_is_appended_and_validated() {
    setup_sandbox
    bash "$SCRIPT" backup --label pre-upgrade
    assert_eq 1 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*-pre-upgrade' -type d | wc -l)" "label"
    assert_exit 1 bash "$SCRIPT" backup --label 'bad/label'
    teardown_sandbox
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL, `not implemented`.

- [ ] **Step 3: Implement archive creation and `cmd_backup`**

Flow: resolve/prepare root; create `"$root/.tmp.$$"`; trap removes it; write excludes to a temp file; create each archive into the temp dir; on any failure exit 2 without moving; `packages_write` from `dpkg --get-selections | awk '$2 == "install" {print $1}' | LC_ALL=C sort`; move temp dir to `$(dir_name_backup "$label")`; then `sums_write` and `manifest_write` inside the final dir; print the shared-storage warning when the root starts with `/sdcard`; run `backup_prune "$root" "$keep"` when `keep > 0`. Run logging is handled once by the dispatcher through `log_run`, not inside `cmd_backup`. `archive_create_home` runs `tar -C "$HOME" -czf "$out" --exclude-from="$exclude_file" .`, and the exclude file always contains the resolved root relative to `$HOME` when the root is under `$HOME`. `manifest_write` uses `uname -m` and `getprop ro.build.version.release 2>/dev/null || echo unknown`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Create verified full-environment backups"
```

---

### Task 4: `verify`, `delete` and `prune`

**Files:**
- Modify: `scripts/termux-backup/lib/storage.sh`
- Modify: `scripts/termux-backup/tests/storage_test.sh`
- Modify: `scripts/termux-backup/termux-backup`

**Interfaces:**
- Consumes: Task 2 and Task 3 storage functions.
- Produces: `sums_verify <dir>` (0 valid, 2 mismatch or missing file), `backup_delete <root> <name> [assume_yes]`, `backup_prune <root> <keep>` (never counts or deletes `safety_pre_restore_*`), `cmd_verify`, `cmd_delete`, `cmd_prune`.

- [ ] **Step 1: Write the failing tests**

```bash
test_verify_rejects_corrupted_archive() {
    setup_sandbox
    bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    printf 'corrupt' >> "$dir/home.tar.gz"
    assert_exit 2 bash "$SCRIPT" verify --name "$(basename "$dir")"
    teardown_sandbox
}

test_verify_rejects_directory_without_sums() {
    setup_sandbox
    mkdir -p "$SANDBOX/backups/termux_backup_20260101_000000"
    assert_exit 2 bash "$SCRIPT" verify --name termux_backup_20260101_000000
    teardown_sandbox
}

test_delete_requires_confirmation_when_not_a_tty() {
    setup_sandbox
    bash "$SCRIPT" backup
    local name
    name=$(basename "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)")
    assert_exit 1 bash "$SCRIPT" delete --name "$name" < /dev/null
    assert_file "$SANDBOX/backups/$name/manifest.json"
    assert_success bash "$SCRIPT" delete --name "$name" --yes
    assert_no_file "$SANDBOX/backups/$name"
    teardown_sandbox
}

test_prune_keeps_newest_and_never_safety() {
    setup_sandbox
    for ts in 20260101_000000 20260102_000000 20260103_000000; do
        mkdir -p "$SANDBOX/backups/termux_backup_$ts"
        touch "$SANDBOX/backups/termux_backup_$ts/SHA256SUMS"
    done
    mkdir -p "$SANDBOX/backups/safety_pre_restore_20260104_000000"
    touch "$SANDBOX/backups/safety_pre_restore_20260104_000000/SHA256SUMS"
    assert_success bash "$SCRIPT" prune --keep 2
    assert_eq 2 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' | wc -l)" "kept"
    assert_no_file "$SANDBOX/backups/termux_backup_20260101_000000"
    assert_file "$SANDBOX/backups/safety_pre_restore_20260104_000000/SHA256SUMS"
    teardown_sandbox
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL on the three new cases.

- [ ] **Step 3: Implement verify, delete, prune**

`sums_verify` runs `(cd "$dir" && sha256sum -c --strict SHA256SUMS)` with output captured; missing `SHA256SUMS` → 2. `backup_delete` prints the resolved absolute path, calls `confirm_word delete "Type delete to remove <name>: "` unless `assume_yes`, then `rm -rf -- "$root/$name"` guarded by `backup_exists` and a name pattern check. `backup_prune` takes `backup_list`, filters `termux_backup_*`, sorts, deletes all but the last `keep` without prompting.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Verify, delete and prune backups safely"
```

---

### Task 5: Restore verification and preflight

**Files:**
- Create: `scripts/termux-backup/lib/restore.sh`
- Create: `scripts/termux-backup/tests/restore_test.sh`
- Modify: `scripts/termux-backup/lib/archive.sh` (entry scan and free space)
- Modify: `scripts/termux-backup/termux-backup`

**Interfaces:**
- Consumes: `sums_verify`, `backup_list`, `backup_exists`, `excludes_list`.
- Produces: `archive_scan <archive>` (0 safe, 2 unsafe), `free_space_bytes <path>`, `restore_resolve <root> <name|--latest>` (echoes directory name; `--latest` ignores `safety_pre_restore_*`), `restore_preflight <backup_path> <force:0|1>` (checks `format_version == 1` when readable, `manifest arch == uname -m` unless force or the manifest is unreadable, free space ≥ 2× `backup_size_bytes`, `$HOME` and `$PREFIX/etc` writable; `die 2` on any failure). All `<dir>` parameters are absolute backup directory paths.
- Command wiring: `cmd_restore` parses `--name --latest --yes --no-packages --no-safety --force --path` in this task but extraction lands in Task 6.

- [ ] **Step 1: Write the failing tests**

```bash
test_corrupted_archive_fails_verification() {
    setup_sandbox
    echo keep > "$HOME/untouched.txt"
    bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    printf 'corrupt' >> "$dir/home.tar.gz"
    source "$SCRIPT_DIR/lib/storage.sh"
    assert_exit 2 sums_verify "$dir"
    teardown_sandbox
}

test_parent_escape_entries_are_rejected() {
    setup_sandbox
    echo payload > "$SANDBOX/outside.txt"
    ( cd "$SANDBOX" && tar -czf "$SANDBOX/evil.tar.gz" --transform='s|^|../|' outside.txt )
    source "$SCRIPT_DIR/lib/archive.sh"
    assert_exit 2 archive_scan "$SANDBOX/evil.tar.gz"
    teardown_sandbox
}

test_arch_mismatch_blocks_unless_forced() {
    setup_sandbox
    bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    sed -i 's/"arch": *"[^"]*"/"arch": "definitely-not-this-arch"/' "$dir/manifest.json"
    source "$SCRIPT_DIR/lib/restore.sh"
    assert_exit 2 restore_preflight "$dir" 0
    assert_success restore_preflight "$dir" 1
    teardown_sandbox
}

test_latest_ignores_safety_archives() {
    setup_sandbox
    mkdir -p "$SANDBOX/backups/termux_backup_20260101_000000"
    touch "$SANDBOX/backups/termux_backup_20260101_000000/SHA256SUMS"
    mkdir -p "$SANDBOX/backups/safety_pre_restore_20260102_000000"
    touch "$SANDBOX/backups/safety_pre_restore_20260102_000000/SHA256SUMS"
    source "$SCRIPT_DIR/lib/restore.sh"
    assert_eq "termux_backup_20260101_000000" "$(restore_resolve "$SANDBOX/backups" --latest)" "latest"
    teardown_sandbox
}
```

`SCRIPT_DIR` is exported by the harness as `$REPO_ROOT/scripts/termux-backup`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL, `archive_scan: command not found`.

- [ ] **Step 3: Implement scan, resolve and preflight**

`archive_scan` lists entries with `tar -tf "$archive"` and fails on `^/` or `(^|/)\.\.(/|$)`. `free_space_bytes` uses `df -Pk "$path" | awk 'NR==2 {print $4 * 1024}'`. `restore_preflight` prints each failed check before exiting 2; when `manifest.json` is absent or lacks an `arch` key it warns and skips the arch guard. `cmd_restore` runs resolve → `sums_verify` → `archive_scan` on each archive → `restore_preflight`, then `die 2 "extraction not implemented"` until Task 6; its helper tests call the library functions directly so a passing test cannot come from that placeholder exit.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Verify restore archives before extraction"
```

---

### Task 6: Extraction, safety archive and confirmation

**Files:**
- Modify: `scripts/termux-backup/lib/restore.sh`
- Modify: `scripts/termux-backup/tests/restore_test.sh`
- Modify: `scripts/termux-backup/termux-backup`

**Interfaces:**
- Consumes: Task 3 archive/storage writers, Task 5 resolve/verify/preflight.
- Produces: `safety_create <root>` (writes `safety_pre_restore_<ts>` with the same six files and `"kind": "safety"`; echoes the directory name), `restore_extract <dir>` (extracts `usr_etc.tar.gz` to `$PREFIX`, then `home.tar.gz` to `$HOME`, with `--no-same-owner --no-overwrite-dir`), `cmd_restore` completing after extraction and calling `packages_install <dir>` (a no-op returning 0 in this task; Task 7 replaces it) unless `--no-packages`.

- [ ] **Step 1: Write the failing tests**

```bash
test_missing_manifest_warns_and_continues() {
    setup_sandbox
    echo original > "$HOME/file.txt"
    bash "$SCRIPT" backup
    local dir name err
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    name=$(basename "$dir")
    echo changed > "$HOME/file.txt"
    rm -f "$dir/manifest.json"
    err=$(bash "$SCRIPT" restore --name "$name" --yes --no-packages 2>&1 >/dev/null) || true
    assert_contains "manifest" "$err" "warning"
    assert_eq "original" "$(cat "$HOME/file.txt")" "restored"
    teardown_sandbox
}

test_restore_merges_and_keeps_extra_files() {
    setup_sandbox
    echo original > "$HOME/shared.txt"
    mkdir -p "$HOME/extra-dir" && echo extra > "$HOME/extra-dir/file"
    bash "$SCRIPT" backup
    echo changed > "$HOME/shared.txt"
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    assert_eq "original" "$(cat "$HOME/shared.txt")" "overwritten"
    assert_file "$HOME/extra-dir/file"
    teardown_sandbox
}

test_restore_creates_safety_archive() {
    setup_sandbox
    echo original > "$HOME/shared.txt"
    bash "$SCRIPT" backup
    echo changed > "$HOME/shared.txt"
    bash "$SCRIPT" restore --latest --yes --no-packages
    assert_eq 1 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'safety_pre_restore_*' -type d | wc -l)" "safety"
    teardown_sandbox
}

test_restore_requires_confirmation_on_a_pipe() {
    setup_sandbox
    bash "$SCRIPT" backup
    assert_exit 1 bash "$SCRIPT" restore --latest --no-packages < /dev/null
    teardown_sandbox
}

test_no_safety_flag_skips_safety_archive() {
    setup_sandbox
    bash "$SCRIPT" backup
    bash "$SCRIPT" restore --latest --yes --no-packages --no-safety
    assert_eq 0 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'safety_pre_restore_*' | wc -l)" "safety"
    teardown_sandbox
}

test_restore_round_trips_filenames_and_symlinks() {
    setup_sandbox
    echo dash > "$HOME/-rf"
    mkdir -p "$SANDBOX/real-termux"
    ln -s "$SANDBOX/real-termux" "$HOME/.termux"
    bash "$SCRIPT" backup
    rm -f "$HOME/-rf" "$HOME/.termux"
    bash "$SCRIPT" restore --latest --yes --no-packages
    assert_eq "dash" "$(cat "$HOME/-rf")" "dash file"
    assert_eq "$SANDBOX/real-termux" "$(readlink "$HOME/.termux")" "symlink"
    teardown_sandbox
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL on restore cases.

- [ ] **Step 3: Implement safety archive, extraction and confirmation**

Before extraction, unless `--no-safety`, build the safety archive in the same temp-then-move pattern as Task 3. Then, unless `--yes`, call `confirm_word restore "Type restore to overwrite <path> and <path>: "`. Extract with `tar -x -f "$dir/home.tar.gz" -C "$HOME" --no-same-owner --no-overwrite-dir`. On extraction failure: `die 2 "extraction failed; safety archive: <name>"`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Restore backups with merge and safety archive"
```

---

### Task 7: Package reinstall and restore log

**Files:**
- Modify: `scripts/termux-backup/lib/restore.sh`
- Modify: `scripts/termux-backup/tests/restore_test.sh`

**Interfaces:**
- Consumes: `packages.txt` from Task 3.
- Produces: `packages_missing <dir>` (one package name per line), `packages_install <dir>` (returns 0, or 3 with the remaining names on stderr), `restore_log_write <dir> <backup_name> <status> <installed_count> <failed_count>` writing `~/.config/termux-backup/last-restore.log`.

- [ ] **Step 1: Write the failing tests**

```bash
test_only_missing_packages_are_installed() {
    setup_sandbox
    printf 'bash\ncurl\n' > "$SANDBOX/packages.txt"
    source "$SCRIPT_DIR/lib/restore.sh"
    assert_eq "curl" "$(FIXTURE_INSTALLED=bash packages_missing "$SANDBOX")" "delta"
    teardown_sandbox
}

test_package_failure_exits_3_and_files_stay() {
    setup_sandbox
    echo before > "$HOME/file.txt"
    bash "$SCRIPT" backup
    echo changed > "$HOME/file.txt"
    PKG_EXIT_CODE=1 bash "$SCRIPT" restore --latest --yes || status=$?
    assert_eq 3 "${status:-0}" "exit"
    assert_eq "before" "$(cat "$HOME/file.txt")" "files restored"
    assert_file "$SANDBOX/pkg.log"
    teardown_sandbox
}

test_no_packages_flag_skips_pkg() {
    setup_sandbox
    bash "$SCRIPT" backup
    bash "$SCRIPT" restore --latest --yes --no-packages
    assert_no_file "$SANDBOX/pkg.log"
    teardown_sandbox
}

test_restore_log_is_written() {
    setup_sandbox
    bash "$SCRIPT" backup
    bash "$SCRIPT" restore --latest --yes --no-packages
    assert_file "$XDG_CONFIG_HOME/termux-backup/last-restore.log"
    teardown_sandbox
}
```

`packages_missing` receives the directory, so the test fixture must create `$SANDBOX/packages.txt`; the helper reads `$1/packages.txt`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL, `packages_missing` missing and exit 0 instead of 3.

- [ ] **Step 3: Implement package delta and log**

`packages_missing` compares `$1/packages.txt` with `dpkg-query -W -f='${Package}\n'` using `comm -23 <(sort ...) <(sort ...)`. `packages_install` formats names into an array and runs `pkg install -y "${names[@]}"`; on failure prints remaining names and returns 3. `cmd_restore` calls it after extraction unless `--no-packages`, captures the status, always writes the restore log, and exits with that status.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Reinstall missing packages after restore"
```

---

### Task 8: Installer, CI and documentation

**Files:**
- Create: `scripts/termux-backup/install.sh`
- Create: `scripts/termux-backup/tests/install_test.sh`
- Create: `scripts/termux-backup/README.md`
- Modify: `.github/workflows/run_tests.yml`
- Modify: `README.md`
- Modify: `docs/ledger/outstanding-work.md`
- Modify: `docs/ledger/manual-checks.md`

**Interfaces:**
- Consumes: the working CLI.
- Produces: `install.sh [--prefix DIR]` copying the directory to `$PREFIX/lib/termux-backup` and symlinking `$PREFIX/bin/termux-backup`; CI job `shell`.

- [ ] **Step 1: Write the failing test**

```bash
test_installer_copies_and_links() {
    setup_sandbox
    assert_success bash "$SCRIPT_DIR/install.sh" --prefix "$PREFIX"
    assert_file "$PREFIX/lib/termux-backup/termux-backup"
    assert_eq "$PREFIX/lib/termux-backup/termux-backup" "$(readlink "$PREFIX/bin/termux-backup")" "symlink"
    assert_success "$PREFIX/bin/termux-backup" status --json
    teardown_sandbox
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL, `install.sh` not found.

- [ ] **Step 3: Implement installer, CI job and docs**

`install.sh` resolves its own directory with `BASH_SOURCE`, copies `termux-backup` and `lib/` (not `tests/`), `chmod 755` the dispatcher, and creates the symlink with `ln -sfn`. Add the `shell` job to `run_tests.yml` exactly as in the spec §13. Write `README.md` covering install, each command, the migration walkthrough, `termux-setup-storage`, the shared-storage secrecy warning, and the safety-archive rollback recipe. Add a short "Backup and restore" section to the root `README.md` linking to `scripts/termux-backup/README.md`. Add ledger entries: phase-2 UI epic, deferred GitHub/GDrive/scheduler/encryption, and manual checks for real storage writes, second-device restore, safety round-trip and revoked permission.

- [ ] **Step 4: Run tests and lint**

Run: `bash scripts/termux-backup/tests/run.sh && shellcheck -S warning scripts/termux-backup/termux-backup scripts/termux-backup/lib/*.sh scripts/termux-backup/tests/*.sh scripts/termux-backup/install.sh`
Expected: all tests pass; shellcheck exits 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup .github/workflows/run_tests.yml README.md docs/ledger
git commit -m "Added(scripts): Install termux-backup and document restore workflow"
```
