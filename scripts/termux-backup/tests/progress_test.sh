#!/usr/bin/env bash
test_progress_records_backup_events_in_order() {
    setup_sandbox
    echo hi > "$HOME/file.txt"
    bash "$SCRIPT" backup --progress-file "$SANDBOX/progress.jsonl"
    local log="$SANDBOX/progress.jsonl"
    assert_contains '"event":"run_start"' "$(sed -n 1p "$log")" "run_start first"
    assert_contains '"command":"backup"' "$(sed -n 1p "$log")" "command"
    assert_contains '"event":"phase","name":"archiving_home"' "$(cat "$log")" "home phase"
    assert_contains '"event":"phase","name":"checksums"' "$(cat "$log")" "checksums phase"
    assert_contains '"event":"run_end","exit_code":0' "$(tail -n 1 "$log")" "run_end last"
    teardown_sandbox
}

test_progress_records_failure_exit_code() {
    setup_sandbox
    TAR_FAIL=1 bash "$SCRIPT" backup --progress-file "$SANDBOX/progress.jsonl" || true
    assert_contains '"event":"run_end","exit_code":2' "$(tail -n 1 "$SANDBOX/progress.jsonl")" "failure code"
    teardown_sandbox
}

test_progress_flag_absent_writes_no_file() {
    setup_sandbox
    bash "$SCRIPT" status >/dev/null 2>&1
    assert_eq "" "$(find "$SANDBOX" -name '*.jsonl' -print -quit)" "unexpected progress file"
    teardown_sandbox
}

test_progress_does_not_pollute_json_stdout() {
    setup_sandbox
    local out
    out=$(bash "$SCRIPT" status --json --progress-file "$SANDBOX/progress.jsonl")
    assert_contains '"tool_version":"1.1.0"' "$out" "json stdout"
    assert_not_contains '"event"' "$out" "events stay out of stdout"
    teardown_sandbox
}
