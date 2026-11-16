#!/usr/bin/env bash
test_corrupted_archive_fails_verification() {
    setup_sandbox
    echo keep >"$HOME/untouched.txt"
    assert_success bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    printf 'corrupt' >>"$dir/home.tar.gz"
    load_libs
    assert_exit 2 sums_verify "$dir"
    teardown_sandbox
}

test_parent_escape_entries_are_rejected() {
    setup_sandbox
    echo payload >"$SANDBOX/outside.txt"
    ( cd "$SANDBOX" && tar -czf "$SANDBOX/evil.tar.gz" --transform='s|^|../|' outside.txt )
    load_libs
    assert_exit 2 archive_scan "$SANDBOX/evil.tar.gz"
    teardown_sandbox
}

test_absolute_entries_are_rejected() {
    setup_sandbox
    echo payload >"$SANDBOX/outside.txt"
    ( cd "$SANDBOX" && tar -czf "$SANDBOX/abs.tar.gz" --transform='s|^|/|' outside.txt )
    load_libs
    assert_exit 2 archive_scan "$SANDBOX/abs.tar.gz"
    teardown_sandbox
}

test_archive_scan_accepts_normal_archive() {
    setup_sandbox
    echo hi >"$HOME/file.txt"
    assert_success bash "$SCRIPT" backup
    load_libs
    assert_success archive_scan "$(find "$SANDBOX/backups" -name 'home.tar.gz')"
    teardown_sandbox
}

test_arch_mismatch_blocks_unless_forced() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    sed -i 's/"arch": *"[^"]*"/"arch": "definitely-not-this-arch"/' "$dir/manifest.json"
    load_libs
    assert_exit 2 restore_preflight "$dir" 0
    assert_success restore_preflight "$dir" 1
    teardown_sandbox
}

test_missing_manifest_warns_and_skips_arch_check() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    rm -f "$dir/manifest.json"
    load_libs
    assert_success restore_preflight "$dir" 0
    teardown_sandbox
}

test_unknown_format_version_blocks() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local dir
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    sed -i 's/"format_version": *[0-9]*/"format_version": 99/' "$dir/manifest.json"
    load_libs
    assert_exit 2 restore_preflight "$dir" 0
    teardown_sandbox
}

test_latest_ignores_safety_archives() {
    setup_sandbox
    mkdir -p "$SANDBOX/backups/termux_backup_20260101_000000"
    touch "$SANDBOX/backups/termux_backup_20260101_000000/SHA256SUMS"
    mkdir -p "$SANDBOX/backups/safety_pre_restore_20260102_000000"
    touch "$SANDBOX/backups/safety_pre_restore_20260102_000000/SHA256SUMS"
    load_libs
    assert_eq "termux_backup_20260101_000000" "$(restore_resolve "$SANDBOX/backups" --latest)" "latest"
    teardown_sandbox
}

test_restore_rejects_corrupted_backup() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local dir name
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    name=$(basename "$dir")
    printf 'corrupt' >>"$dir/home.tar.gz"
    assert_exit 2 bash "$SCRIPT" restore --name "$name" --yes
    teardown_sandbox
}

test_restore_requires_a_selector() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" restore
    teardown_sandbox
}

test_restore_latest_without_backups_fails_cleanly() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" restore --latest --yes
    teardown_sandbox
}

test_missing_manifest_warns_and_continues() {
    setup_sandbox
    echo original >"$HOME/file.txt"
    assert_success bash "$SCRIPT" backup
    local dir name err
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    name=$(basename "$dir")
    echo changed >"$HOME/file.txt"
    rm -f "$dir/manifest.json"
    err=$(bash "$SCRIPT" restore --name "$name" --yes --no-packages 2>&1 >/dev/null) || true
    assert_contains "manifest" "$err" "warning"
    assert_eq "original" "$(cat "$HOME/file.txt")" "restored"
    teardown_sandbox
}

test_restore_merges_and_keeps_extra_files() {
    setup_sandbox
    echo original >"$HOME/shared.txt"
    mkdir -p "$HOME/extra-dir" && echo extra >"$HOME/extra-dir/file"
    assert_success bash "$SCRIPT" backup
    echo changed >"$HOME/shared.txt"
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    assert_eq "original" "$(cat "$HOME/shared.txt")" "overwritten"
    assert_file "$HOME/extra-dir/file"
    teardown_sandbox
}

test_restore_creates_safety_archive() {
    setup_sandbox
    echo original >"$HOME/shared.txt"
    assert_success bash "$SCRIPT" backup
    echo changed >"$HOME/shared.txt"
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    assert_eq 1 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'safety_pre_restore_*' -type d | wc -l)" "safety"
    local safety
    safety=$(find "$SANDBOX/backups" -maxdepth 1 -name 'safety_pre_restore_*' -type d)
    assert_file "$safety/manifest.json"
    assert_file "$safety/SHA256SUMS"
    assert_file "$safety/packages.txt"
    assert_file "$safety/home.tar.gz"
    ( cd "$safety" && sha256sum -c --strict SHA256SUMS >/dev/null )
    assert_contains '"kind": "safety"' "$(cat "$safety/manifest.json")" "kind"
    teardown_sandbox
}

test_safety_archive_holds_pre_restore_state() {
    setup_sandbox
    echo original >"$HOME/shared.txt"
    assert_success bash "$SCRIPT" backup
    echo changed >"$HOME/shared.txt"
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    local safety
    safety=$(find "$SANDBOX/backups" -maxdepth 1 -name 'safety_pre_restore_*' -type d)
    assert_eq "changed" "$(tar -xzOf "$safety/home.tar.gz" ./shared.txt)" "safety content"
    teardown_sandbox
}

test_restore_requires_confirmation_on_a_pipe() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    assert_exit 1 bash "$SCRIPT" restore --latest --no-packages </dev/null
    teardown_sandbox
}

test_no_safety_flag_skips_safety_archive() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages --no-safety
    assert_eq 0 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'safety_pre_restore_*' | wc -l)" "safety"
    teardown_sandbox
}

test_restore_round_trips_filenames_and_symlinks() {
    setup_sandbox
    echo dash >"$HOME/-rf"
    mkdir -p "$SANDBOX/real-termux"
    ln -s "$SANDBOX/real-termux" "$HOME/.termux"
    assert_success bash "$SCRIPT" backup
    rm -f "$HOME/-rf" "$HOME/.termux"
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    assert_eq "dash" "$(cat "$HOME/-rf")" "dash file"
    assert_eq "$SANDBOX/real-termux" "$(readlink "$HOME/.termux")" "symlink"
    teardown_sandbox
}

test_restore_json_reports_names() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local out name
    name=$(basename "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)")
    out=$(bash "$SCRIPT" restore --latest --yes --no-packages --json)
    assert_contains '"name":"'"$name"'"' "$out" "name"
    assert_contains '"safety":"safety_pre_restore_' "$out" "safety"
    teardown_sandbox
}

test_only_missing_packages_are_installed() {
    setup_sandbox
    printf 'bash\ncurl\n' >"$SANDBOX/packages.txt"
    load_libs
    assert_eq "curl" "$(FIXTURE_INSTALLED=bash packages_missing "$SANDBOX")" "delta"
    teardown_sandbox
}

test_package_install_calls_pkg_with_missing_names() {
    setup_sandbox
    printf 'bash\ncurl\n' >"$SANDBOX/packages.txt"
    load_libs
    FIXTURE_INSTALLED=bash packages_install "$SANDBOX"
    assert_eq "install -y curl" "$(cat "$SANDBOX/pkg.log")" "pkg args"
    teardown_sandbox
}

test_package_failure_returns_3_with_names() {
    setup_sandbox
    printf 'bash\ncurl\n' >"$SANDBOX/packages.txt"
    load_libs
    local err
    err=$(FIXTURE_INSTALLED=bash PKG_EXIT_CODE=1 packages_install "$SANDBOX" 2>&1 >/dev/null) || true
    assert_exit 3 env FIXTURE_INSTALLED=bash PKG_EXIT_CODE=1 "$BASH" -c \
        "source '$SCRIPT_DIR/lib/common.sh'; source '$SCRIPT_DIR/lib/archive.sh'; source '$SCRIPT_DIR/lib/storage.sh'; source '$SCRIPT_DIR/lib/restore.sh'; packages_install '$SANDBOX'"
    assert_contains "curl" "$err" "remaining names"
    teardown_sandbox
}

test_package_failure_exits_3_and_files_stay() {
    setup_sandbox
    echo before >"$HOME/file.txt"
    assert_success bash "$SCRIPT" backup
    echo changed >"$HOME/file.txt"
    local status=0
    PKG_EXIT_CODE=1 bash "$SCRIPT" restore --latest --yes || status=$?
    assert_eq 3 "$status" "exit"
    assert_eq "before" "$(cat "$HOME/file.txt")" "files restored"
    assert_file "$SANDBOX/pkg.log"
    teardown_sandbox
}

test_no_packages_flag_skips_pkg() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    assert_no_file "$SANDBOX/pkg.log"
    teardown_sandbox
}

test_restore_log_is_written() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    assert_success bash "$SCRIPT" restore --latest --yes --no-packages
    assert_file "$XDG_CONFIG_HOME/termux-backup/last-restore.log"
    assert_contains "status=ok" "$(cat "$XDG_CONFIG_HOME/termux-backup/last-restore.log")" "status"
    teardown_sandbox
}

test_restore_log_records_package_failure() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    PKG_EXIT_CODE=1 bash "$SCRIPT" restore --latest --yes || true
    assert_contains "status=packages-failed" \
        "$(cat "$XDG_CONFIG_HOME/termux-backup/last-restore.log")" "status"
    teardown_sandbox
}

test_dpkg_query_failure_is_not_treated_as_empty() {
    setup_sandbox
    printf 'bash\ncurl\n' >"$SANDBOX/packages.txt"
    load_libs
    local rc=0
    ( DPKG_QUERY_EXIT=1 packages_missing "$SANDBOX" ) >/dev/null 2>&1 || rc=$?
    assert_eq 3 "$rc" "dpkg failure status"
    teardown_sandbox
}

test_restore_with_dpkg_failure_restores_files_and_exits_3() {
    setup_sandbox
    echo before >"$HOME/file.txt"
    assert_success bash "$SCRIPT" backup
    echo changed >"$HOME/file.txt"
    local status=0 err
    err=$(DPKG_QUERY_EXIT=1 bash "$SCRIPT" restore --latest --yes 2>&1 >/dev/null) || status=$?
    assert_eq 3 "$status" "exit"
    assert_eq "before" "$(cat "$HOME/file.txt")" "files restored"
    assert_contains "package" "$err" "warning"
    assert_no_file "$SANDBOX/pkg.log"
    teardown_sandbox
}

test_restore_prompt_lists_name_size_and_targets() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local name err
    name=$(basename "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)")
    err=$(bash "$SCRIPT" restore --latest --no-packages </dev/null 2>&1 >/dev/null) || true
    assert_contains "$name" "$err" "backup name"
    assert_contains "Archive size" "$err" "archive size"
    assert_contains "Packages to install" "$err" "package delta"
    assert_contains "$HOME" "$err" "home target"
    teardown_sandbox
}
