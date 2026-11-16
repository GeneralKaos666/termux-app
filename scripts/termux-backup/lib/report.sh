# shellcheck shell=bash
# Human and JSON rendering for list and status. Functions only.

list_parse_path() {
    local path=""
    while [ "$#" -gt 0 ]; do
        case $1 in
            --path)
                [ "$#" -ge 2 ] || die 1 "list: --path needs a value"
                path=$2
                shift 2
                ;;
            --json | --quiet) shift ;;
            *)
                die 1 "list: unknown option: $1"
                ;;
        esac
    done
    printf '%s' "$path"
}

cmd_list() {
    local path root first=1 name size pkgs kind created
    path=$(list_parse_path "$@")
    root=$(root_resolve "$path")

    if [ "${JSON:-0}" -eq 1 ]; then
        printf '{"root":"%s","backups":[' "$(json_escape "$root")"
        while IFS= read -r name; do
            [ -n "$name" ] || continue
            [ "$first" -eq 1 ] || printf ','
            first=0
            size=$(backup_size_bytes "$root/$name")
            pkgs=$(packages_count "$root/$name")
            kind=$(backup_kind "$name")
            created=$(backup_created_at "$name")
            printf '{"name":"%s","created_at":"%s","size":%s,"packages":%s,"kind":"%s"}' \
                "$(json_escape "$name")" "$created" "$size" "$pkgs" "$kind"
        done < <(backup_list "$root")
        printf ']}\n'
        return 0
    fi

    if [ -z "$(backup_list "$root")" ]; then
        log_info "No backups in $root"
        return 0
    fi
    printf '%-44s %-20s %14s %8s\n' "NAME" "CREATED" "SIZE" "PACKAGES"
    while IFS= read -r name; do
        [ -n "$name" ] || continue
        size=$(backup_size_bytes "$root/$name")
        pkgs=$(packages_count "$root/$name")
        created=$(backup_created_at "$name")
        printf '%-44s %-20s %14s %8s\n' "$name" "$created" "$size" "$pkgs"
    done < <(backup_list "$root")
}

status_parse_path() {
    local path=""
    while [ "$#" -gt 0 ]; do
        case $1 in
            --path)
                [ "$#" -ge 2 ] || die 1 "status: --path needs a value"
                path=$2
                shift 2
                ;;
            --json | --quiet) shift ;;
            *)
                die 1 "status: unknown option: $1"
                ;;
        esac
    done
    printf '%s' "$path"
}

cmd_status() {
    local path root writable=false access=false backups compression keep free
    path=$(status_parse_path "$@")
    root=$(root_resolve "$path")

    if [ -d "$root" ] && [ -w "$root" ]; then
        writable=true
        access=true
    elif [ -w "$(dirname "$root")" ]; then
        access=true
    fi

    backups=$(backup_list "$root" | wc -l | tr -d ' ')
    compression=$(config_get compression || true)
    [ -n "$compression" ] || compression="gz"
    keep=$(config_get keep || true)
    [[ $keep =~ ^[0-9]+$ ]] || keep=5
    free=$(free_space_bytes "$root")
    [ -n "$free" ] || free=0

    if [ "${JSON:-0}" -eq 1 ]; then
        printf '{"tool_version":"%s","root":"%s","writable":%s,"storage_access":%s,"backups":%s,"compression":"%s","keep":%s,"free_space":%s}\n' \
            "$TOOL_VERSION" "$(json_escape "$root")" "$writable" "$access" "$backups" \
            "$(json_escape "$compression")" "$keep" "$free"
        return 0
    fi

    printf 'tool_version:  %s\n' "$TOOL_VERSION"
    printf 'root:          %s\n' "$root"
    printf 'writable:      %s\n' "$writable"
    printf 'storage_access:%s\n' "$access"
    printf 'backups:       %s\n' "$backups"
    printf 'compression:   %s\n' "$compression"
    printf 'keep:          %s\n' "$keep"
    printf 'free_space:    %s\n' "$free"
}
