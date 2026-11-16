#!/usr/bin/env bash
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
    assert_success bash "$SCRIPT" backup
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
    assert_exit 2 env TAR_FAIL=1 bash "$SCRIPT" backup
    assert_eq "" "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*')" "partial backup"
    teardown_sandbox
}

test_dash_and_space_names_and_symlink_survive() {
    setup_sandbox
    echo dash > "$HOME/-rf"
    echo spaced > "$HOME/my notes.txt"
    mkdir -p "$SANDBOX/real-termux"
    ln -s "$SANDBOX/real-termux" "$HOME/.termux"
    assert_success bash "$SCRIPT" backup
    local listing
    listing=$(tar -tzf "$(find "$SANDBOX/backups" -name home.tar.gz)")
    assert_contains "./-rf" "$listing" "leading dash"
    assert_contains "./my notes.txt" "$listing" "space in name"
    teardown_sandbox
}

test_exclude_secrets_omits_ssh() {
    setup_sandbox
    mkdir -p "$HOME/.ssh" && echo key > "$HOME/.ssh/id_ed25519"
    assert_success bash "$SCRIPT" backup --exclude-secrets
    assert_not_contains ".ssh/id_ed25519" "$(tar -tzf "$(find "$SANDBOX/backups" -name home.tar.gz)")" "secret"
    teardown_sandbox
}

test_no_home_and_no_config_skip_sources() {
    setup_sandbox
    echo hi > "$HOME/file.txt"
    echo conf > "$PREFIX/etc/termux.properties"
    assert_success bash "$SCRIPT" backup --no-home --no-config
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
    assert_success bash "$SCRIPT" backup
    assert_contains "command=backup result=ok" "$(cat "$XDG_CONFIG_HOME/termux-backup/backup.log")" "log line"
    teardown_sandbox
}

test_label_is_appended_and_validated() {
    setup_sandbox
    bash "$SCRIPT" backup --label pre-upgrade
    assert_eq 1 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*-pre-upgrade' -type d | wc -l)" "label"
    assert_exit 1 bash "$SCRIPT" backup --label 'bad/label'
    teardown_sandbox
}

test_second_backup_in_the_same_second_gets_its_own_directory() {
    setup_sandbox
    export DATE_FIXTURE=20260101_000000
    assert_success bash "$SCRIPT" backup --label dup
    assert_success bash "$SCRIPT" backup --label dup
    assert_eq 2 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' -type d | wc -l)" "two backups"
    assert_file "$SANDBOX/backups/termux_backup_20260101_000000-dup/SHA256SUMS"
    assert_file "$SANDBOX/backups/termux_backup_20260101_000000-dup-2/SHA256SUMS"
    assert_eq 2 "$(bash "$SCRIPT" list --json | grep -o '"name"' | wc -l)" "listed"
    teardown_sandbox
}

test_root_equal_to_home_is_refused() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" backup --path "$HOME"
    assert_no_file "$HOME/SHA256SUMS"
    teardown_sandbox
}

test_root_with_trailing_slash_inside_home_is_not_archived() {
    setup_sandbox
    export TERMUX_BACKUP_ROOT="$HOME/backups/"
    assert_success bash "$SCRIPT" backup
    local listing
    listing=$(tar -tzf "$(find "$HOME/backups" -name home.tar.gz)")
    assert_not_contains "backups/" "$listing" "self archive"
    teardown_sandbox
}

test_backup_summary_reports_package_count() {
    setup_sandbox
    local out
    out=$(bash "$SCRIPT" backup 2>&1 >/dev/null)
    assert_contains "packages" "$out" "package count"
    teardown_sandbox
}

test_symlinked_config_directory_is_stored_as_a_link() {
    setup_sandbox
    mkdir -p "$SANDBOX/real-termux"
    echo secret >"$SANDBOX/real-termux/history"
    ln -s "$SANDBOX/real-termux" "$HOME/.termux"
    assert_success bash "$SCRIPT" backup
    local listing
    listing=$(tar -tzf "$(find "$SANDBOX/backups" -name home.tar.gz)")
    assert_contains "./.termux" "$listing" "link entry"
    assert_not_contains "real-termux/history" "$listing" "dereferenced"
    teardown_sandbox
}

test_invalid_keep_is_a_usage_error() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT" backup --keep 3x
    assert_eq 0 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' | wc -l)" "no backup"
    teardown_sandbox
}

test_extra_excludes_are_literal_patterns() {
    setup_sandbox
    mkdir -p "$XDG_CONFIG_HOME/termux-backup"
    printf 'extra_excludes=*.log\n' >"$XDG_CONFIG_HOME/termux-backup/config.conf"
    echo noise >"$SANDBOX/work/x.log"
    load_libs
    local out
    out=$(cd "$SANDBOX/work" && excludes_list 0)
    assert_contains "*.log" "$out" "literal pattern"
    assert_not_contains "x.log" "$out" "glob expanded"
    teardown_sandbox
}

test_failed_checksum_write_leaves_no_backup() {
    setup_sandbox
    SHA256SUM_FAIL=1 bash "$SCRIPT" backup || true
    assert_eq 0 "$(find "$SANDBOX/backups" -maxdepth 1 -name 'termux_backup_*' | wc -l)" "leftover"
    teardown_sandbox
}
