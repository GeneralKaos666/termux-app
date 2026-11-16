#!/usr/bin/env bash
test_installer_copies_and_links() {
    setup_sandbox
    assert_success bash "$SCRIPT_DIR/install.sh" --prefix "$PREFIX"
    assert_file "$PREFIX/lib/termux-backup/termux-backup"
    assert_file "$PREFIX/lib/termux-backup/lib/common.sh"
    assert_no_file "$PREFIX/lib/termux-backup/tests"
    assert_eq "$PREFIX/lib/termux-backup/termux-backup" \
        "$(readlink "$PREFIX/bin/termux-backup")" "symlink"
    assert_success "$PREFIX/bin/termux-backup" status --json
    teardown_sandbox
}

test_installer_is_idempotent() {
    setup_sandbox
    assert_success bash "$SCRIPT_DIR/install.sh" --prefix "$PREFIX"
    assert_success bash "$SCRIPT_DIR/install.sh" --prefix "$PREFIX"
    assert_eq "$PREFIX/lib/termux-backup/termux-backup" \
        "$(readlink "$PREFIX/bin/termux-backup")" "symlink"
    teardown_sandbox
}

test_installer_rejects_unknown_option() {
    setup_sandbox
    assert_exit 1 bash "$SCRIPT_DIR/install.sh" --nope
    teardown_sandbox
}
