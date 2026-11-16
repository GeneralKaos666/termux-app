#!/usr/bin/env bash
# Restore: preflight checks and command wiring. Functions only.

manifest_field() {
    local file=$1 key=$2
    sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "$file" | head -n 1
}

manifest_number() {
    local file=$1 key=$2
    sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" "$file" | head -n 1
}

restore_resolve() {
    local root=$1 selector=${2:-} name
    case $selector in
        '' | --latest)
            name=$(backup_list "$root" | awk '/^termux_backup_/ { last = $0 } END { print last }')
            [ -n "$name" ] || return 1
            printf '%s' "$name"
            ;;
        *)
            printf '%s' "$selector"
            ;;
    esac
}

restore_preflight() {
    local dir=$1 force=${2:-0}
    local manifest fv arch size free failed=0

    if [ ! -d "$dir" ]; then
        log_error "restore: no such backup directory: $dir"
        return 2
    fi

    manifest="$dir/manifest.json"
    if [ -f "$manifest" ]; then
        fv=$(manifest_number "$manifest" format_version)
        if [ -n "$fv" ] && [ "$fv" != "$FORMAT_VERSION" ]; then
            log_error "restore: unsupported format_version $fv (expected $FORMAT_VERSION)"
            failed=1
        fi
        arch=$(manifest_field "$manifest" arch)
        if [ -z "$arch" ]; then
            log_warn "warning: manifest has no arch field; skipping architecture check"
        elif [ "$force" -ne 1 ] && [ "$arch" != "$(uname -m)" ]; then
            log_error "restore: backup arch $arch does not match $(uname -m); pass --force to override"
            failed=1
        fi
    else
        log_warn "warning: no manifest.json; skipping format and architecture checks"
    fi

    size=$(backup_size_bytes "$dir")
    free=$(free_space_bytes "$HOME" || true)
    if [ -z "$free" ]; then
        log_warn "warning: cannot determine free space for $HOME; skipping space check"
    elif [ "$free" -lt $((size * 2)) ]; then
        log_error "restore: not enough free space in $HOME (need $((size * 2)) bytes, have $free)"
        failed=1
    fi

    [ -w "$HOME" ] || {
        log_error "restore: $HOME is not writable"
        failed=1
    }
    [ -d "$PREFIX/etc" ] && [ ! -w "$PREFIX/etc" ] && {
        log_error "restore: $PREFIX/etc is not writable"
        failed=1
    }

    [ "$failed" -eq 0 ] || die 2 "restore: preflight checks failed"
    return 0
}

restore_parse() {
    OPT_NAME=""
    OPT_PATH=""
    OPT_YES=0
    OPT_LATEST=0
    OPT_FORCE=0
    OPT_NO_PACKAGES=0
    OPT_NO_SAFETY=0
    OPT_HELP=0
    while [ "$#" -gt 0 ]; do
        case $1 in
            --name)
                [ "$#" -ge 2 ] || die 1 "restore: --name needs a value"
                OPT_NAME=$2
                shift 2
                ;;
            --path)
                [ "$#" -ge 2 ] || die 1 "restore: --path needs a value"
                OPT_PATH=$2
                shift 2
                ;;
            --latest)
                OPT_LATEST=1
                shift
                ;;
            --yes | -y)
                # shellcheck disable=SC2034 # consumed by the extraction step
                OPT_YES=1
                shift
                ;;
            --force)
                OPT_FORCE=1
                shift
                ;;
            --no-packages)
                # shellcheck disable=SC2034 # consumed by the extraction step
                OPT_NO_PACKAGES=1
                shift
                ;;
            --no-safety)
                # shellcheck disable=SC2034 # consumed by the extraction step
                OPT_NO_SAFETY=1
                shift
                ;;
            --json | --quiet)
                shift
                ;;
            -h | --help)
                OPT_HELP=1
                shift
                ;;
            *)
                die 1 "restore: unknown option: $1"
                ;;
        esac
    done
}

restore_usage() {
    cat <<'EOF'
Usage: termux-backup restore (--name NAME | --latest) [options]

  --latest         Restore the newest backup (safety archives excluded)
  --yes            Do not ask for confirmation
  --force          Restore even when the recorded architecture differs
  --no-packages    Do not reinstall the recorded package list
  --no-safety      Do not create a safety archive before restoring
  --path DIR       Use DIR as the backup root
  --json           Print one JSON object
EOF
}

cmd_restore() {
    local root name dir archive
    restore_parse "$@"
    if [ "$OPT_HELP" -eq 1 ]; then
        restore_usage
        return 0
    fi
    if [ -n "$OPT_NAME" ] && [ "$OPT_LATEST" -eq 1 ]; then
        die 1 "restore: --name and --latest cannot be combined"
    fi

    root=$(root_resolve "$OPT_PATH")
    if [ "$OPT_LATEST" -eq 1 ]; then
        name=$(restore_resolve "$root" --latest) || die 1 "restore: no backup found in $root"
    else
        [ -n "$OPT_NAME" ] || die 1 "restore: --name or --latest is required"
        name=$OPT_NAME
        backup_name_valid "$name" || die 1 "restore: invalid backup name: $name"
    fi
    dir="$root/$name"
    backup_exists "$root" "$name" || die 1 "restore: no such backup: $name"

    sums_verify "$dir" || die 2 "restore: checksum verification failed for $name"

    for archive in "$dir"/*.tar.*; do
        [ -f "$archive" ] || continue
        archive_scan "$archive" || die 2 "restore: unsafe path in ${archive##*/}"
    done

    restore_preflight "$dir" "$OPT_FORCE"

    die 2 "restore: extraction not implemented"
}
