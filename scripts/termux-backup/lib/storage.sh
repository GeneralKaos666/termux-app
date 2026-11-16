# shellcheck shell=bash
# Backup storage: paths, directory names, listing and sizing. Functions only.

config_file() {
    printf '%s/termux-backup/config.conf' "${XDG_CONFIG_HOME:-$HOME/.config}"
}

config_get() {
    local key=$1 file line
    file=$(config_file)
    [ -f "$file" ] || return 1
    while IFS= read -r line || [ -n "$line" ]; do
        [[ $line =~ ^([a-z_]+)=(.*)$ ]] || continue
        if [ "${BASH_REMATCH[1]}" = "$key" ]; then
            printf '%s' "${BASH_REMATCH[2]}"
            return 0
        fi
    done <"$file"
    return 1
}

root_resolve() {
    local cli=${1:-} cfg=""
    if [ -n "$cli" ]; then
        printf '%s' "$cli"
        return 0
    fi
    if [ -n "${TERMUX_BACKUP_ROOT:-}" ]; then
        printf '%s' "$TERMUX_BACKUP_ROOT"
        return 0
    fi
    if cfg=$(config_get root) && [ -n "$cfg" ]; then
        printf '%s' "$cfg"
        return 0
    fi
    printf '%s' "$DEFAULT_ROOT"
}

root_prepare() {
    local root=$1
    mkdir -p "$root" 2>/dev/null || die 2 "cannot create backup root: $root"
    [ -d "$root" ] && [ -w "$root" ] || die 2 "backup root is not writable: $root"
}

dir_name_backup() {
    local stamp label=${1:-}
    stamp=$(date -u +%Y%m%d_%H%M%S)
    if [ -n "$label" ]; then
        printf 'termux_backup_%s-%s' "$stamp" "$label"
    else
        printf 'termux_backup_%s' "$stamp"
    fi
}

dir_name_safety() {
    printf 'safety_pre_restore_%s' "$(date -u +%Y%m%d_%H%M%S)"
}

backup_exists() {
    local root=$1 name=$2
    [ -n "$name" ] && [ -d "$root/$name" ]
}

backup_list() {
    local root=$1 dir name
    [ -d "$root" ] || return 0
    for dir in "$root"/*; do
        [ -d "$dir" ] || continue
        [ -f "$dir/SHA256SUMS" ] || continue
        name=${dir##*/}
        case $name in
            termux_backup_* | safety_pre_restore_*) printf '%s\n' "$name" ;;
        esac
    done | LC_ALL=C sort
}

backup_size_bytes() {
    local dir=$1 file total=0
    for file in "$dir"/*.tar.*; do
        [ -f "$file" ] || continue
        total=$((total + $(wc -c <"$file")))
    done
    printf '%s' "$total"
}

packages_count() {
    local dir=$1
    if [ -f "$dir/packages.txt" ]; then
        wc -l <"$dir/packages.txt" | tr -d ' '
    else
        printf '0'
    fi
}

backup_kind() {
    case $1 in
        safety_pre_restore_*) printf 'safety' ;;
        *) printf 'backup' ;;
    esac
}

backup_created_at() {
    local name=$1 stamp
    if [[ $name =~ ^(termux_backup|safety_pre_restore)_([0-9]{4})([0-9]{2})([0-9]{2})_([0-9]{2})([0-9]{2})([0-9]{2}) ]]; then
        stamp="${BASH_REMATCH[2]}-${BASH_REMATCH[3]}-${BASH_REMATCH[4]}T${BASH_REMATCH[5]}:${BASH_REMATCH[6]}:${BASH_REMATCH[7]}Z"
    else
        stamp="unknown"
    fi
    printf '%s' "$stamp"
}

free_space_bytes() {
    local path=$1 probe=$1
    [ -d "$probe" ] || probe=$(dirname "$probe")
    df -Pk "$probe" 2>/dev/null | awk 'NR == 2 { print $4 * 1024 }'
}
