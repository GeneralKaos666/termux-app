#!/usr/bin/env bash
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

test_list_on_missing_root_is_empty_not_an_error() {
    setup_sandbox
    rm -rf "$SANDBOX/backups"
    local out
    out=$(bash "$SCRIPT" list --json)
    assert_contains '"backups":[]' "$out" "empty list"
    assert_exit 0 bash "$SCRIPT" list --json
    teardown_sandbox
}

test_verify_rejects_corrupted_archive() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local dir name
    dir=$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)
    name=$(basename "$dir")
    printf 'corrupt' >>"$dir/home.tar.gz"
    assert_exit 2 bash "$SCRIPT" verify --name "$name"
    teardown_sandbox
}

test_verify_accepts_intact_backup() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local name
    name=$(basename "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)")
    assert_success bash "$SCRIPT" verify --name "$name"
    teardown_sandbox
}

test_verify_rejects_directory_without_sums() {
    setup_sandbox
    mkdir -p "$SANDBOX/backups/termux_backup_20260101_000000"
    assert_exit 2 bash "$SCRIPT" verify --name termux_backup_20260101_000000
    teardown_sandbox
}

test_verify_rejects_unknown_name() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" verify --name termux_backup_19990101_000000
    teardown_sandbox
}

test_delete_requires_confirmation_when_not_a_tty() {
    setup_sandbox
    assert_success bash "$SCRIPT" backup
    local name
    name=$(basename "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d)")
    assert_exit 1 bash "$SCRIPT" delete --name "$name" </dev/null
    assert_file "$SANDBOX/backups/$name/manifest.json"
    assert_success bash "$SCRIPT" delete --name "$name" --yes
    assert_no_file "$SANDBOX/backups/$name"
    teardown_sandbox
}

test_delete_rejects_path_traversal_name() {
    setup_sandbox
    touch "$SANDBOX/backups/keepme"
    assert_exit 1 bash "$SCRIPT" delete --name '../backups' --yes
    assert_file "$SANDBOX/backups/keepme"
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
