# termux-backup

Back up and restore a whole Termux environment: `$HOME`, `$PREFIX/etc`, and the list of installed
packages. One bash script, no dependencies beyond what Termux already ships (`bash`, `tar`, `gzip`/
`bzip2`/`xz`, `dpkg`, `pkg`, `sha256sum`).

Phase 1 is script-only. The in-app settings screen that drives this script is phase 2 and is not part
of this directory yet.

## Install

```bash
cd scripts/termux-backup
./install.sh                 # copies to $PREFIX/lib/termux-backup, symlinks $PREFIX/bin/termux-backup
./install.sh --prefix /other # install somewhere else
```

The build of `termux-backup` at the top level of this directory is the source; re-run `install.sh`
after changing the script. `tests/` is never installed.

## Storage access

Backups default to `/sdcard/termux-backups`. On Android 11 and above Termux needs shared-storage
access before that path is usable:

```bash
termux-setup-storage
```

If the root is not writable, `backup` and `restore` exit 2 with the path that failed. `status` shows
`storage_access: false` in the same situation.

Shared storage is world-readable by other apps on many devices. Archives can contain tokens, keys
and shell history; use `--exclude-secrets`, or choose a root inside `$HOME` (for example
`--path "$HOME/termux-backups"`) when that matters more than surviving an app-data wipe.

## Commands

| Command | What it does |
|---|---|
| `termux-backup backup` | Create a backup of packages, `$HOME` and `$PREFIX/etc` |
| `termux-backup restore` | Restore a backup, merging over the current environment |
| `termux-backup list` | List backups, newest last |
| `termux-backup verify` | Check a backup against its `SHA256SUMS` |
| `termux-backup delete` | Delete one backup, after a typed confirmation |
| `termux-backup prune` | Keep only the newest N backups |
| `termux-backup status` | Show root, writability, free space and backup count |

Every command accepts `--json` (one JSON object on stdout, for phase-2 UI use), `--quiet`, and
`--path DIR` to override the backup root. `--json` and `--quiet` cannot be combined.

### backup

```bash
termux-backup backup                       # termux_backup_20261115_031500
termux-backup backup --label pre-upgrade   # termux_backup_20261115_031500-pre-upgrade
termux-backup backup --keep 3              # prune to the three newest afterwards
termux-backup backup --compression xz
termux-backup backup --exclude-secrets     # skip .ssh, .gnupg, tokens, cloud credentials
termux-backup backup --no-home --no-config # package list only
```

A backup directory contains `home.tar.*`, `usr_etc.tar.*`, `packages.txt`, `manifest.json` and
`SHA256SUMS`. `SHA256SUMS` is authoritative; `manifest.json` records the tool version, creation time,
label, architecture, Android release and compression.

The archive is built in `$root/.tmp.<pid>` and moved into place only when every step succeeded, so an
interrupted or failed backup never appears in `list`.

Default excludes: `.cache`, `.npm`, `node_modules`, `.gradle`, `.android`, `.thumbnails`, `DCIM`,
`Movies`, `Music`, `.local/share/Trash`, `.local/lib`, `.java`, `.m2`, `.ivy2`, `go/pkg`,
`.rustup/toolchains`, `.cargo/registry`, `.cargo/git`, `__pycache__`. Add more with an
`extra_excludes` line in `$XDG_CONFIG_HOME/termux-backup/config.conf` (space-separated). `$PREFIX/lib`
is never archived because it is architecture-bound; packages are reinstalled from `packages.txt`.

### restore

```bash
termux-backup restore --latest --yes
termux-backup restore --name termux_backup_20261115_031500-pre-upgrade
termux-backup restore --latest --no-safety       # skip the pre-restore snapshot
termux-backup restore --latest --no-packages     # files only
termux-backup restore --latest --force           # ignore an architecture mismatch
```

Before touching anything, `restore` runs every check and refuses on the first failure:

1. `SHA256SUMS` must match (exit 2 otherwise).
2. Every archive entry must be relative — no `/`, no `..` (exit 2 otherwise).
3. `manifest.json`, when readable, must say `format_version: 1` and the same architecture unless
   `--force` is given. An unreadable or missing manifest warns and skips those two checks.
4. `$HOME` and `$PREFIX/etc` must be writable, and `$HOME` must have at least twice the backup size
   free.

Then it writes a safety archive of the **current** environment (`safety_pre_restore_<timestamp>`),
asks for the typed word `restore` unless `--yes`, and extracts `usr_etc.tar.*` into `$PREFIX` followed
by `home.tar.*` into `$HOME`. Extraction merges: files from the backup overwrite their namesakes and
anything else present is left alone.

Missing packages from `packages.txt` are installed with `pkg install -y` unless `--no-packages`. The
files stay restored even when that install fails; the command then exits 3 and prints the packages
that are still missing. Every restore appends a line to
`$XDG_CONFIG_HOME/termux-backup/last-restore.log` with `status=ok` or `status=packages-failed`.

### list, verify, delete, prune, status

```bash
termux-backup list
termux-backup list --json
termux-backup verify --name termux_backup_20261115_031500
termux-backup delete --name termux_backup_20261115_031500        # asks for the word delete
termux-backup delete --name termux_backup_20261115_031500 --yes
termux-backup prune --keep 5
termux-backup status --json
```

`verify` and `delete` need `--name`; `prune` takes `--keep N` and never counts or deletes
`safety_pre_restore_*` archives. `status` reports `tool_version`, `root`, `writable`,
`storage_access`, `backups`, `compression`, `keep` and `free_space` in bytes.

## Moving to a new device

1. On the old device: `termux-setup-storage` (once) and `termux-backup backup --label move`.
2. Copy the whole backup directory to the same path on the new device, for example with a USB cable,
   `rsync`, or `scp`. Keep the directory contents intact.
3. On the new device: install Termux and run `pkg update` once so `pkg` and `dpkg` work.
4. `termux-backup verify --name termux_backup_<...>-move`.
5. `termux-backup restore --name termux_backup_<...>-move --force --yes`.

`--force` is the usual choice here because the two devices may report different architectures in the
manifest. The package list is reinstalled first; anything that no longer exists in the repositories is
named at the end with exit code 3.

## Rolling back a restore

The safety archive holds the state from just before the restore, and it restores like any other
backup:

```bash
termux-backup list                       # find safety_pre_restore_<timestamp>
termux-backup restore --name safety_pre_restore_<timestamp> --yes --no-packages
```

`--no-packages` is intentional: the package set was already the one you had before the restore.

## Configuration

`$XDG_CONFIG_HOME/termux-backup/config.conf` (defaults to `~/.config/termux-backup/config.conf`)
accepts one `key=value` per line:

```ini
root=/sdcard/termux-backups
compression=gz
keep=5
extra_excludes=my-cache big-files
```

The `TERMUX_BACKUP_ROOT` environment variable overrides `root`; `--path` overrides both.
Logs live next to the config: `backup.log` records every command and its exit code,
`last-restore.log` records the last restore.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Bad usage, unknown backup name, or refused confirmation |
| 2 | Storage, checksum, archive-safety or extraction failure |
| 3 | Files restored, but one or more packages could not be installed |

## Tests

```bash
bash scripts/termux-backup/tests/run.sh
shellcheck -S warning scripts/termux-backup/termux-backup scripts/termux-backup/lib/*.sh \
    scripts/termux-backup/tests/*.sh scripts/termux-backup/install.sh
```

Every case runs in a `mktemp` sandbox with its own `HOME`, `PREFIX`, backup root and a `PATH` shim
that fakes `dpkg`, `dpkg-query`, `pkg` and `tar`, so the suite never touches the real installation and
never needs the network.
