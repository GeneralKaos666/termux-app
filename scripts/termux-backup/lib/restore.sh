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
        log_error "termux-backup: restore: no such backup directory: $dir"
        return 2
    fi

    manifest="$dir/manifest.json"
    if [ -f "$manifest" ]; then
        fv=$(manifest_number "$manifest" format_version)
        if [ -n "$fv" ] && [ "$fv" != "$FORMAT_VERSION" ]; then
            log_error "termux-backup: restore: unsupported format_version $fv (expected $FORMAT_VERSION)"
            failed=1
        fi
        arch=$(manifest_field "$manifest" arch)
        if [ -z "$arch" ]; then
            log_warn "termux-backup: warning: manifest has no arch field; skipping architecture check"
        elif [ "$force" -ne 1 ] && [ "$arch" != "$(uname -m)" ]; then
            log_error "termux-backup: restore: backup arch $arch does not match $(uname -m); pass --force to override"
            failed=1
        fi
    else
        log_warn "termux-backup: warning: no manifest.json; skipping format and architecture checks"
    fi

    size=$(backup_size_bytes "$dir")
    free=$(free_space_bytes "$HOME" || true)
    if [ -z "$free" ]; then
        log_warn "termux-backup: warning: cannot determine free space for $HOME; skipping space check"
    elif [ "$free" -lt $((size * 2)) ]; then
        log_error "termux-backup: restore: not enough free space in $HOME (need $((size * 2)) bytes, have $free)"
        failed=1
    fi

    [ -w "$HOME" ] || {
        log_error "termux-backup: restore: $HOME is not writable"
        failed=1
    }
    [ -d "$PREFIX/etc" ] && [ ! -w "$PREFIX/etc" ] && {
        log_error "termux-backup: restore: $PREFIX/etc is not writable"
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

restore_extract() {
    local dir=$1 archive
    for archive in "$dir"/usr_etc.tar.*; do
        [ -f "$archive" ] || continue
        tar -x -f "$archive" -C "$PREFIX" --no-same-owner --no-overwrite-dir || return 1
    done
    for archive in "$dir"/home.tar.*; do
        [ -f "$archive" ] || continue
        tar -x -f "$archive" -C "$HOME" --no-same-owner --no-overwrite-dir || return 1
    done
}

packages_missing() {
    local dir=$1 installed
    [ -f "$dir/packages.txt" ] || return 0
    installed=$(dpkg-query -W -f='${Package}\n' 2>/dev/null) || return 3
    comm -23 <(LC_ALL=C sort -u "$dir/packages.txt") \
        <(printf '%s\n' "$installed" | LC_ALL=C sort -u)
}

packages_install() {
    local dir=$1 name missing
    local -a names=()
    if ! missing=$(packages_missing "$dir"); then
        printf 'termux-backup: restore: cannot read the installed package list (dpkg-query failed)\n' >&2
        return 3
    fi
    while IFS= read -r name; do
        [ -n "$name" ] && names+=("$name")
    done <<<"$missing"
    [ "${#names[@]}" -gt 0 ] || return 0
    if pkg install -y "${names[@]}" >/dev/null 2>&1; then
        return 0
    fi
    printf '%s\n' "${names[@]}" >&2
    return 3
}

restore_log_write() {
    local dir=$1 name=$2 status=$3 installed=$4 failed=$5 log
    log="${XDG_CONFIG_HOME:-$HOME/.config}/termux-backup/last-restore.log"
    mkdir -p "$(dirname "$log")" 2>/dev/null || return 0
    printf '%s backup=%s status=%s installed=%s failed=%s source=%s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$name" "$status" "$installed" "$failed" "$dir" \
        >>"$log" 2>/dev/null || true
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
    if ! backup_exists "$root" "$name"; then
        log_error "termux-backup: restore: no such backup: $name"
        local available
        available=$(backup_list "$root" | awk '/^termux_backup_/')
        if [ -n "$available" ]; then
            printf 'Available backups in %s:\n%s\n' "$root" "$available" >&2
        else
            printf 'No backups found in %s\n' "$root" >&2
        fi
        exit 1
    fi

    sums_verify "$dir" || die 2 "restore: $(sums_problem "$dir")"

    for archive in "$dir"/*.tar.*; do
        [ -f "$archive" ] || continue
        archive_scan "$archive" || die 2 "restore: unsafe path in ${archive##*/}"
    done

    restore_preflight "$dir" "$OPT_FORCE"

    if [ "$OPT_YES" -ne 1 ]; then
        local size delta
        size=$(human_size "$(backup_size_bytes "$dir")")
        delta=$(packages_missing "$dir" 2>/dev/null | awk 'NF { n++ } END { print n + 0 }') || delta=0
        log_info "Backup:              $name"
        log_info "Archive size:        $size"
        log_info "Packages to install: $delta"
        log_info "Restore into:        $HOME and $PREFIX/etc"
        confirm_word restore "Type restore to overwrite $HOME and $PREFIX/etc: " ||
            die 1 "restore: aborted"
    fi

    local safety=""
    if [ "$OPT_NO_SAFETY" -eq 0 ]; then
        local compression
        compression=$(manifest_field "$dir/manifest.json" compression 2>/dev/null || true)
        [ -n "$compression" ] || compression=gz
        safety=$(safety_create "$root" "$compression") ||
            die 2 "restore: could not create safety archive; nothing was restored"
        if [ "${QUIET:-0}" -eq 0 ] && [ "${JSON:-0}" -eq 0 ]; then
            log_info "Safety archive: $safety"
        fi
    fi

    if ! restore_extract "$dir"; then
        if [ -n "$safety" ]; then
            die 2 "restore: extraction failed; safety archive: $safety"
        fi
        die 2 "restore: extraction failed"
    fi

    local installed=0 failed=0 requested=0 status=0 status_word=ok failed_names="" missing_names=""
    if [ "$OPT_NO_PACKAGES" -eq 0 ]; then
        if ! missing_names=$(packages_missing "$dir"); then
            status=3
            status_word=packages-failed
            log_warn "termux-backup: warning: cannot read the installed package list (dpkg-query failed); packages were not reinstalled"
        else
            requested=$(printf '%s\n' "$missing_names" | awk 'NF { n++ } END { print n + 0 }')
            installed=$requested
            if ! failed_names=$(packages_install "$dir" 2>&1 >/dev/null); then
                status=3
                status_word=packages-failed
                failed=$(printf '%s\n' "$failed_names" | awk 'NF { n++ } END { print n + 0 }')
                installed=$((requested - failed))
                log_warn "termux-backup: warning: $failed package(s) could not be installed: $(printf '%s' "$failed_names" | tr '\n' ' ')"
            fi
        fi
    fi

    restore_log_write "$dir" "$name" "$status_word" "$installed" "$failed"

    if [ "${JSON:-0}" -eq 1 ]; then
        printf '{"name":"%s","root":"%s","safety":"%s","packages_installed":%s,"packages_failed":%s}\n' \
            "$(json_escape "$name")" "$(json_escape "$root")" \
            "$(json_escape "$safety")" "$installed" "$failed"
    elif [ "${QUIET:-0}" -eq 0 ]; then
        log_info "Restored $name"
    fi
    return "$status"
}
