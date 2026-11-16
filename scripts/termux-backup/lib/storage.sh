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
    local probe=$1
    [ -d "$probe" ] || probe=$(dirname "$probe")
    df -Pk "$probe" 2>/dev/null | awk 'NR == 2 { print $4 * 1024 }'
}

sums_write() {
    local dir=$1
    if compgen -G "$dir/*.tar.*" >/dev/null; then
        (
            cd "$dir" || exit 1
            sha256sum -- *.tar.* >SHA256SUMS.tmp && mv -- SHA256SUMS.tmp SHA256SUMS
        )
    else
        : >"$dir/SHA256SUMS"
    fi
}

manifest_write() {
    local dir=$1 kind=$2 label=$3 compression=$4
    local arch release created file size first=1 archives="" excludes="" exclude
    arch=$(uname -m)
    release=$(getprop ro.build.version.release 2>/dev/null || true)
    [ -n "$release" ] || release="unknown"
    created=$(date -u +%Y-%m-%dT%H:%M:%SZ)

    for file in "$dir"/*.tar.*; do
        [ -f "$file" ] || continue
        size=$(wc -c <"$file" | tr -d ' ')
        [ "$first" -eq 1 ] || archives+=", "
        first=0
        archives+="\"$(json_escape "${file##*/}")\": $size"
    done

    first=1
    while IFS= read -r exclude; do
        [ -n "$exclude" ] || continue
        [ "$first" -eq 1 ] || excludes+=", "
        first=0
        excludes+="\"$(json_escape "$exclude")\""
    done < <(excludes_list 0)

    printf '{\n' >"$dir/manifest.json"
    printf '  "format_version": %s,\n' "$FORMAT_VERSION" >>"$dir/manifest.json"
    printf '  "tool_version": "%s",\n' "$TOOL_VERSION" >>"$dir/manifest.json"
    printf '  "created_at": "%s",\n' "$created" >>"$dir/manifest.json"
    printf '  "kind": "%s",\n' "$(json_escape "$kind")" >>"$dir/manifest.json"
    printf '  "label": "%s",\n' "$(json_escape "$label")" >>"$dir/manifest.json"
    printf '  "arch": "%s",\n' "$(json_escape "$arch")" >>"$dir/manifest.json"
    printf '  "android_release": "%s",\n' "$(json_escape "$release")" >>"$dir/manifest.json"
    printf '  "compression": "%s",\n' "$(json_escape "$compression")" >>"$dir/manifest.json"
    printf '  "sources": {"home": "%s", "etc": "%s"},\n' \
        "$(json_escape "$HOME")" "$(json_escape "$PREFIX")" >>"$dir/manifest.json"
    printf '  "excludes": [%s],\n' "$excludes" >>"$dir/manifest.json"
    printf '  "archives": {%s}\n' "$archives" >>"$dir/manifest.json"
    printf '}\n' >>"$dir/manifest.json"
}

backup_prune() {
    local root=$1 keep=$2 excess i
    local -a names=()
    [[ $keep =~ ^[0-9]+$ ]] || die 1 "prune: --keep needs a number"
    [ "$keep" -gt 0 ] || return 0
    local name
    while IFS= read -r name; do
        case $name in
            termux_backup_*) names+=("$name") ;;
        esac
    done < <(backup_list "$root")
    excess=$((${#names[@]} - keep))
    [ "$excess" -gt 0 ] || return 0
    for ((i = 0; i < excess; i++)); do
        rm -rf -- "${root:?}/${names[i]}"
    done
}

