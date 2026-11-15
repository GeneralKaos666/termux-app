# shellcheck shell=bash
# Shared constants and helpers. Defines functions only.

TOOL_VERSION="1.0.0"
FORMAT_VERSION=1
DEFAULT_ROOT="/sdcard/termux-backups"

color_init() {
    C_RESET=""
    C_RED=""
    C_GREEN=""
    C_YELLOW=""
    [ -t 2 ] || return 0
    [ -n "${NO_COLOR:-}" ] && return 0
    C_RESET=$'\033[0m'
    C_RED=$'\033[31m'
    C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'
}

log_info() { printf '%s\n' "$*" >&2; }
log_warn() { printf '%s%s%s\n' "${C_YELLOW:-}" "$*" "${C_RESET:-}" >&2; }
log_error() { printf '%s%s%s\n' "${C_RED:-}" "$*" "${C_RESET:-}" >&2; }

die() {
    local code=$1
    shift
    log_error "termux-backup: $*"
    exit "$code"
}

json_escape() {
    local s=$1 out="" ch i
    for ((i = 0; i < ${#s}; i++)); do
        ch=${s:i:1}
        case $ch in
            \\) out+='\\' ;;
            '"') out+='\"' ;;
            $'\n') out+='\n' ;;
            $'\r') out+='\r' ;;
            $'\t') out+='\t' ;;
            *) out+=$ch ;;
        esac
    done
    printf '%s' "$out"
}

confirm_word() {
    local word=$1 prompt=$2 answer=""
    [ -t 0 ] || return 1
    printf '%s' "$prompt" >&2
    IFS= read -r answer || return 1
    [ "$answer" = "$word" ]
}

log_run() {
    local command=$1 code=$2 result="ok" dir
    [ "$code" -eq 0 ] || result="fail"
    dir="${XDG_CONFIG_HOME:-$HOME/.config}/termux-backup"
    mkdir -p "$dir" 2>/dev/null || return 0
    printf '%s command=%s result=%s code=%s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$command" "$result" "$code" \
        >>"$dir/backup.log" 2>/dev/null || true
}
