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

test_colors_are_enabled_on_a_tty() {
    setup_sandbox
    local out
    out=$(script -qec "bash '$SCRIPT' verify --name termux_backup_19990101_000000" /dev/null </dev/null 2>&1 || true)
    assert_contains $'\033[31m' "$out" "red error on a tty"
    teardown_sandbox
}

test_no_color_disables_colors_on_a_tty() {
    setup_sandbox
    local out
    out=$(NO_COLOR=1 script -qec "bash '$SCRIPT' verify --name termux_backup_19990101_000000" /dev/null </dev/null 2>&1 || true)
    assert_not_contains $'\033[31m' "$out" "no color"
    teardown_sandbox
}
