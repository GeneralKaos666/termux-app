#!/usr/bin/env bash
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
