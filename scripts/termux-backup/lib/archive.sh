# shellcheck shell=bash
# Archive creation: excludes, tarballs, package list, sizes. Functions only.

DEFAULT_EXCLUDES=(
    .cache
    .npm
    __pycache__
    node_modules
    .gradle
    .android
    .thumbnails
    DCIM
    Movies
    Music
    .local/share/Trash
    .local/lib
    .java
    .m2
    .ivy2
    go/pkg
    .rustup/toolchains
    .cargo/registry
    .cargo/git
)

SECRET_EXCLUDES=(
    .ssh
    .gnupg
    .netrc
    .git-credentials
    .config/gh
    .npmrc
    .pypirc
    .aws
    .docker/config.json
)

excludes_list() {
    local secrets=${1:-0} pattern extras=""
    for pattern in "${DEFAULT_EXCLUDES[@]}"; do
        printf '%s\n' "$pattern"
    done
    extras=$(config_get extra_excludes 2>/dev/null || true)
    if [ -n "$extras" ]; then
        for pattern in $extras; do
            [ -n "$pattern" ] && printf '%s\n' "$pattern"
        done
    fi
    if [ "$secrets" -eq 1 ]; then
        for pattern in "${SECRET_EXCLUDES[@]}"; do
            printf '%s\n' "$pattern"
        done
    fi
}

tar_flags_for() {
    case $1 in
        gz) printf '%s' "-z" ;;
        bz2) printf '%s' "-j" ;;
        xz) printf '%s' "-J" ;;
        *) die 1 "unsupported compression: $1" ;;
    esac
}

archive_name_for() {
    local kind=$1 compression=$2 ext
    case $compression in
        gz) ext=gz ;;
        bz2) ext=bz2 ;;
        xz) ext=xz ;;
        *) die 1 "unsupported compression: $compression" ;;
    esac
    case $kind in
        home) printf 'home.tar.%s' "$ext" ;;
        etc) printf 'usr_etc.tar.%s' "$ext" ;;
        *) die 1 "unknown archive kind: $kind" ;;
    esac
}

archive_create_home() {
    local out=$1 compression=$2 exclude_file=$3 flag
    flag=$(tar_flags_for "$compression")
    tar -C "$HOME" "$flag" -cf "$out" --exclude-from="$exclude_file" .
}

archive_create_etc() {
    local out=$1 compression=$2 flag
    flag=$(tar_flags_for "$compression")
    tar -C "$PREFIX" "$flag" -cf "$out" etc
}

packages_write() {
    local dir=$1
    dpkg --get-selections | awk '$2 == "install" { print $1 }' | LC_ALL=C sort >"$dir/packages.txt"
}

human_size() {
    local bytes=${1:-0}
    if [ "$bytes" -lt 1024 ]; then
        printf '%s B' "$bytes"
    elif [ "$bytes" -lt 1048576 ]; then
        printf '%s KB' "$((bytes / 1024))"
    elif [ "$bytes" -lt 1073741824 ]; then
        printf '%s MB' "$((bytes / 1048576))"
    else
        printf '%s GB' "$((bytes / 1073741824))"
    fi
}

backup_usage() {
    cat <<'EOF'
Usage: termux-backup backup [options]

  --label NAME          Append a label to the backup directory name
  --keep N              Keep only the newest N backups (0 disables pruning)
  --compression CODEC   gz (default), bz2 or xz
  --exclude-secrets     Skip .ssh, .gnupg, tokens and cloud credentials
  --no-home             Do not archive $HOME
  --no-config           Do not archive $PREFIX/etc
  --path DIR            Use DIR as the backup root
  --json                Print one JSON object
EOF
}

backup_create_body() {
    set -euo pipefail
    local tmp=$1 root=$2 label=$3 compression=$4 secrets=$5 no_home=$6 no_config=$7
    local excludes="$tmp/excludes" dir_name rel archive

    mkdir -p "$tmp"
    trap 'rm -rf -- "$tmp"' EXIT

    excludes_list "$secrets" >"$excludes" || return 1
    if [ -n "$label" ]; then
        dir_name=$(dir_name_backup "$label")
    else
        dir_name=$(dir_name_backup)
    fi

    case $root in
        "$HOME"/*)
            rel=${root#"$HOME"/}
            if [ -n "$rel" ]; then
                printf '%s\n%s/*\n' "$rel" "$rel" >>"$excludes"
            fi
            ;;
    esac

    if [ "$no_config" -eq 0 ]; then
        archive="$(archive_name_for etc "$compression")"
        archive_create_etc "$tmp/$archive" "$compression" || return 1
    fi
    if [ "$no_home" -eq 0 ]; then
        archive="$(archive_name_for home "$compression")"
        archive_create_home "$tmp/$archive" "$compression" "$excludes" || return 1
    fi

    packages_write "$tmp" || return 1
    rm -f -- "$excludes"
    mv -- "$tmp" "$root/$dir_name" || return 1
    trap - EXIT
    sums_write "$root/$dir_name" || return 1
    manifest_write "$root/$dir_name" backup "$label" "$compression" || return 1
    printf '%s' "$dir_name"
}

cmd_backup() {
    local label="" keep="" compression="" secrets=0 no_home=0 no_config=0 path=""
    while [ "$#" -gt 0 ]; do
        case $1 in
            --label)
                [ "$#" -ge 2 ] || die 1 "backup: --label needs a value"
                label=$2
                shift 2
                ;;
            --keep)
                [ "$#" -ge 2 ] || die 1 "backup: --keep needs a value"
                keep=$2
                shift 2
                ;;
            --compression)
                [ "$#" -ge 2 ] || die 1 "backup: --compression needs a value"
                compression=$2
                shift 2
                ;;
            --exclude-secrets)
                secrets=1
                shift
                ;;
            --no-home)
                no_home=1
                shift
                ;;
            --no-config)
                no_config=1
                shift
                ;;
            --path)
                [ "$#" -ge 2 ] || die 1 "backup: --path needs a value"
                path=$2
                shift 2
                ;;
            --json | --quiet)
                shift
                ;;
            -h | --help)
                backup_usage
                return 0
                ;;
            *)
                die 1 "backup: unknown option: $1"
                ;;
        esac
    done

    if [ -n "$label" ] && [[ ! $label =~ ^[A-Za-z0-9._-]+$ ]]; then
        die 1 "backup: invalid label (use letters, digits, dot, underscore, dash): $label"
    fi
    if [ -z "$compression" ]; then
        compression=$(config_get compression 2>/dev/null || true)
    fi
    [ -n "$compression" ] || compression=gz
    case $compression in
        gz | bz2 | xz) ;;
        *) die 1 "backup: unsupported compression: $compression" ;;
    esac
    if [ -z "$keep" ]; then
        keep=$(config_get keep 2>/dev/null || true)
    fi
    [[ $keep =~ ^[0-9]+$ ]] || keep=5

    local root tmp name
    root=$(root_resolve "$path")
    root_prepare "$root"
    case $root in
        /sdcard* | /storage/*)
            log_warn "warning: backups on shared storage are readable by other apps"
            ;;
    esac
    tmp="$root/.tmp.$$"

    if ! name=$(backup_create_body "$tmp" "$root" "$label" "$compression" "$secrets" "$no_home" "$no_config"); then
        rm -rf -- "$tmp"
        die 2 "backup failed; no backup was kept"
    fi
    rm -rf -- "$tmp"

    if [ "$keep" -gt 0 ]; then
        backup_prune "$root" "$keep"
    fi

    local size
    size=$(backup_size_bytes "$root/$name")
    if [ "${JSON:-0}" -eq 1 ]; then
        printf '{"name":"%s","root":"%s","size":%s,"packages":%s}\n' \
            "$(json_escape "$name")" "$(json_escape "$root")" "$size" "$(packages_count "$root/$name")"
    elif [ "${QUIET:-0}" -eq 0 ]; then
        log_info "Created $name ($(human_size "$size"))"
    fi
}

archive_scan() {
    local archive=$1 listing entry
    [ -f "$archive" ] || return 2
    listing=$(tar -tf "$archive" 2>/dev/null) || return 2
    while IFS= read -r entry; do
        [ -n "$entry" ] || continue
        entry=${entry#./}
        case $entry in
            /* | .. | ../* | */.. | */../*) return 2 ;;
        esac
    done <<<"$listing"
    return 0
}
