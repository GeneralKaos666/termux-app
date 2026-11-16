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
