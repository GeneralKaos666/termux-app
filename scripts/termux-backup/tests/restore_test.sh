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
