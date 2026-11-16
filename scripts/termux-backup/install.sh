#!/usr/bin/env bash
# Installs termux-backup into a Termux prefix. Never edits the sources it copies.
set -euo pipefail

SRC_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TARGET_PREFIX=${PREFIX:-}

usage() {
    cat <<'EOF'
Usage: install.sh [--prefix DIR]

  --prefix DIR   Install into DIR (default: $PREFIX)
  -h, --help     Show this help
EOF
}

while [ "$#" -gt 0 ]; do
    case $1 in
        --prefix)
            [ "$#" -ge 2 ] || {
                printf 'install.sh: --prefix needs a value\n' >&2
                exit 1
            }
            TARGET_PREFIX=$2
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            printf 'install.sh: unknown option: %s\n' "$1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

[ -n "$TARGET_PREFIX" ] || {
    printf 'install.sh: PREFIX is not set; pass --prefix DIR\n' >&2
    exit 1
}

lib_dir="$TARGET_PREFIX/lib/termux-backup"
bin_dir="$TARGET_PREFIX/bin"

mkdir -p "$lib_dir/lib" "$bin_dir"
cp "$SRC_DIR/termux-backup" "$lib_dir/termux-backup"
cp "$SRC_DIR"/lib/*.sh "$lib_dir/lib/"
chmod 755 "$lib_dir/termux-backup"
ln -sfn "$lib_dir/termux-backup" "$bin_dir/termux-backup"

printf 'Installed %s\n' "$bin_dir/termux-backup"
