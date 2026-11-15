# Termux Backup and Restore — Script Phase Design

- **Status:** approved for planning
- **Date:** 2026-11-15
- **Scope:** phase 1 (CLI script). Phase 2 (in-app UI) is outlined in §15.
- **Reference:** [`djunekz/termux-sync`](https://github.com/djunekz/termux-sync) — full-environment
  archives, `manifest.json`, `SHA256SUMS`, local/GitHub/Drive backends, pruning, scheduling.

## 1. Context and intent

The app ships with `android:allowBackup="false"`, so nothing in a Termux installation is copied by
Android backup. Moving a setup to a new device, or recovering from a broken `$PREFIX`, currently
means rebuilding packages and dotfiles by hand.

The intent is a portable backup of a complete Termux environment that can be restored on any device
running the same CPU architecture, delivered first as a zero-dependency CLI and later as an in-app
screen.

Success criteria:

- One command produces a self-verifying backup of packages, `$HOME` and `$PREFIX/etc`.
- One command restores that backup on a fresh install and reports what it changed.
- A corrupted or truncated archive is rejected before anything is written.
- Restoring never silently deletes user data.
- The CLI's `--json` output is stable enough for phase 2 to consume.

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Delivery order | Script first, UI later | The UI is a thin wrapper over a proven engine; the engine's interface must be stable first. |
| Default scope | Full environment | Matches termux-sync and the migration use case. |
| Storage | Local only, default `/sdcard/termux-backups` | No credentials or network in phase 1; the path survives app data wipes. |
| Restore semantics | Merge + pre-restore safety archive | Overwrites changed files without deleting extras; rollback exists without a complex transaction. |
| Scheduling | None in phase 1 | Boot/cron automation belongs with the UI phase. |
| `$PREFIX/lib` | Not archived | Architecture-bound; apt restores it exactly. Archiving adds size and cross-arch corruption risk. |
| Verification | `SHA256SUMS` is authoritative, `manifest.json` is informational | Restore must not depend on JSON parsing, so no `jq` dependency. |
| Secrets | Included by default, `--exclude-secrets` opt-out | Fidelity matters for migration; the shared-storage warning makes the exposure explicit. |

## 3. Non-goals for phase 1

- Cloud backends (GitHub, Google Drive, rclone).
- Encryption or password-protected archives.
- Scheduled or boot-triggered backups.
- In-app UI, SAF import/export, or notification progress.
- Selective per-package restore, differential/incremental archives.
- Archiving `$PREFIX/lib`, `$PREFIX/bin` or the bootstrap.

## 4. Command surface

```
termux-backup backup  [--label L] [--keep N] [--compression gz|bz2|xz]
                      [--exclude-secrets] [--no-home] [--no-config]
                      [--path DIR] [--json|--quiet]
termux-backup restore [--name NAME | --latest] [--yes] [--no-packages]
                      [--no-safety] [--force] [--path DIR] [--json|--quiet]
termux-backup list    [--path DIR] [--json]
termux-backup verify  --name NAME [--path DIR] [--json]
termux-backup delete  --name NAME [--yes] [--path DIR] [--json]
termux-backup prune   --keep N [--path DIR] [--json]
termux-backup status  [--path DIR] [--json]
```

Defaults:

- `--keep 5` after a successful backup; `--keep 0` disables pruning.
- `--compression gz`.
- `--label` is restricted to `[A-Za-z0-9._-]+` and appended to the directory name.
- `--path` overrides environment `TERMUX_BACKUP_ROOT`, which overrides the config file, which
  defaults to `/sdcard/termux-backups`.
- `--json` and `--quiet` are mutually exclusive.

Config file: `~/.config/termux-backup/config.conf`, `key=value`, parsed without `source`:

```
root=/sdcard/termux-backups
compression=gz
keep=5
extra_excludes=.my-local-cache
```

## 5. On-disk format

```
/sdcard/termux-backups/
  termux_backup_20261115_101530/            # no label
  termux_backup_20261115_101530-before-upgrade/
    manifest.json
    SHA256SUMS
    packages.txt
    home.tar.gz
    usr_etc.tar.gz
  safety_pre_restore_20261115_104512/
    ... same six files ...
```

`manifest.json`:

```json
{
  "format_version": 1,
  "tool_version": "1.0.0",
  "created_at": "2026-11-15T10:15:30Z",
  "kind": "backup",
  "label": "before-upgrade",
  "arch": "aarch64",
  "android_release": "14",
  "compression": "gz",
  "sources": {"home": "/data/data/com.termux/files/home",
               "etc": "/data/data/com.termux/files/usr/etc"},
  "excludes": [".cache", ".gradle", "..."],
  "archives": {"home.tar.gz": 123456789, "usr_etc.tar.gz": 1234}
}
```

`SHA256SUMS` uses the standard `sha256sum` format (`<hash>  <name>`), one line per archive, and is
generated last. A backup directory missing `SHA256SUMS` is reported as unusable and is never
restored or pruned. The manifest is read only for the format and arch guards: if it is missing or
unreadable, restore warns and skips the arch check instead of blocking.

Archive layout inside the tarballs:

- `home.tar.gz` is created as `tar -C "$HOME" -czf ... .`, so paths are relative to `$HOME`.
- `usr_etc.tar.gz` is created as `tar -C "$PREFIX" -czf ... etc`, so restore must use `-C "$PREFIX"`.

Both archives preserve symlinks and modes. Extraction uses `--no-same-owner` and
`--no-overwrite-dir`.

## 6. Backup semantics

Sources: `dpkg --get-selections | awk '$2 == "install" {print $1}'` sorted into `packages.txt`;
`$HOME` unless `--no-home`; `$PREFIX/etc` unless `--no-config`.

Default excludes, relative to `$HOME`:

```
.cache .npm __pycache__ node_modules .gradle .android .thumbnails
DCIM Movies Music .local/share/Trash .local/lib .java .m2 .ivy2
go/pkg .rustup/toolchains .cargo/registry .cargo/git
```

Local file ignore patterns from `extra_excludes` are appended, and the backup root itself is always
excluded when it lives under `$HOME`.

`--exclude-secrets` adds: `.ssh .gnupg .netrc .git-credentials .config/gh .npmrc .pypirc
.aws .docker/config.json`.

Flow:

1. Resolve and create the root; fail with exit 2 if it is not writable. On `/sdcard` without
   storage access, print the `termux-setup-storage` hint.
2. Write archives into a temporary directory under the root, then move them into the final
   directory name so a failed run leaves no half-valid backup.
3. Compute checksums, write `SHA256SUMS`, write `manifest.json`, write `packages.txt`.
4. Prune older backups down to `--keep N`, keeping `safety_pre_restore_*` directories out of the
   count.
5. Print a summary: name, sizes, package count, and the shared-storage warning when the root is
   under `/sdcard`.

## 7. Restore semantics

1. Resolve `--name`, or `--latest` = newest directory by timestamp prefix (safety archives
   excluded). Unknown name exits 1 with the available names.
2. Verify every archive against `SHA256SUMS`. Any mismatch exits 2 before any write.
3. Pre-scan archive entries with `tar -tzf`; reject absolute paths, `..` components and entries
   outside the archive root (exit 2).
4. Preflight: `format_version` supported, architecture match (mismatch stops unless `--force`),
   free space at least twice the archive size, `$HOME` and `$PREFIX/etc` writable.
5. Safety archive unless `--no-safety`, written as `safety_pre_restore_<ts>` with the same excludes,
   sums and manifest (`"kind": "safety"`). It is never pruned automatically.
6. Unless `--yes`, print the backup name, archive size, package delta and both target paths, then
   require the literal word `restore` on stdin.
7. Extract `usr_etc.tar.gz` to `$PREFIX`, then `home.tar.gz` to `$HOME`. Merge only: nothing is
   deleted, existing files are overwritten.
8. Reinstall missing packages (§8), then write `~/.config/termux-backup/last-restore.log`.

Exit codes: `0` success, `1` usage/unknown name, `2` verification or preflight failure, `3` partial
(files restored, package installation failed).

Failure rules: a mid-extract failure aborts, leaves the partial state in place, prints the safety
archive path, and exits 2. Phase 1 does not roll back automatically; restoring the safety archive is
the documented rollback and `list` shows it like any other entry.

## 8. Package restoration

`packages.txt` minus the names reported by `dpkg-query -W -f='${Package}\n'` is installed in one
`pkg install -y <list>` call. `--no-packages` skips this step. On failure the command prints the
remaining names, their count, and exits 3; extracted files stay in place. Package installation is
always last so a network failure never blocks file restoration.

## 9. Module layout and interfaces

```
scripts/termux-backup/
  termux-backup          dispatcher: usage, global flag parsing, subcommand routing
  lib/common.sh          log/ok/warn/die, --json emit, arg helpers, config load, tty/color
  lib/storage.sh         root resolution, dir naming, manifest read/write, sums, list/delete/prune
  lib/archive.sh         exclude list, tar create/extract, size, free space, entry scan
  lib/restore.sh         resolve, verify, preflight, safety archive, extract, package delta
  lib/report.sh          list/status table and JSON rendering
  install.sh             copies dir to $PREFIX/lib/termux-backup, symlinks $PREFIX/bin/termux-backup
  tests/run.sh           runner; tests/{cli,backup,restore,storage}_test.sh
  README.md              usage, migration walkthrough, safety and shared-storage warnings
```

Rules: bash 5, `set -euo pipefail`, no dependency beyond `tar`, `gzip`/`bzip2`/`xz`,
`sha256sum`, `dpkg`, `pkg`. Each lib file exposes functions only and never executes on source.
`TOOL_VERSION` and `FORMAT_VERSION` live in `lib/common.sh`. Newer Bash-only features are allowed
(Termux ships Bash 5 and CI uses Bash 5); `/bin/sh` compatibility is not required.

`--json` emits one object per command on stdout; human messages go to stderr so the JSON stream
stays parseable.

## 10. Error handling and logging

- Every error is one line on stderr prefixed `termux-backup:` with the failing path or archive name.
- `NO_COLOR` or a non-tty disables color automatically.
- `~/.config/termux-backup/backup.log` appends one line per run (timestamp, command, name, result).
- `last-restore.log` holds the full restore record from §7 step 8.
- `status` reports tool version, root path, writability, storage-access state, compression, keep
  count, free space, and backup count.

## 11. Security considerations

- `/sdcard/termux-backups` is readable by other apps holding shared-storage access. Every backup to
  that root prints a warning; the README states that archives contain shell history, dotfiles and
  credentials unless `--exclude-secrets` is used.
- The tool never stores credentials and never uploads anything in phase 1.
- Restore requires the literal `restore` confirmation unless `--yes`, and always verifies sums and
  entry paths first. Absolute or parent-escaping tar entries are rejected.
- Safety archives are created before extraction and are never auto-pruned.
- Deleting requires `--yes` or an interactive `delete` confirmation; the name is printed before the
  prompt. With stdin not a tty and no `--yes`, `delete` and `restore` exit 1 instead of waiting.

## 12. Testing

Each test creates a temporary sandbox with its own `HOME`, `PREFIX` and backup root, and prepends a
shim directory to `PATH` containing fake `dpkg`, `dpkg-query` and `pkg`, so tests never touch the
real installation or the network. Restore tests always pass `--no-packages` unless the case is
specifically about package handling. The runner prints one line per case and exits non-zero on the
first failure.

Cases:

- `cli`: unknown subcommand, unknown flag, `--json` with `--quiet`, help text, exit 1.
- `backup`: default name shape, label append and rejection of invalid labels, excludes respected,
  `--exclude-secrets`, `--no-home` and `--no-config`, sums present and correct, `keep` prunes the
  oldest and skips safety archives.
- `storage`: `list` and `list --json`, `verify` on a valid and a corrupted archive, `delete` without
  confirmation, `prune --keep`, missing `SHA256SUMS` treated as unusable.
- `restore`: corrupted archive exits 2 with nothing extracted, absolute/`..` entry rejected,
  merge keeps an extra file, safety archive created and listed, arch mismatch blocks without
  `--force`, `--no-safety`, package failure exits 3, `--latest` selection.

`shellcheck -S warning` runs over all scripts. JSON output is validated with `jq` when present and
with `grep` assertions otherwise, so the test suite keeps no hard dependency.

## 13. CI

Add a `shell` job to `.github/workflows/run_tests.yml` running on `ubuntu-latest`:

```yaml
- uses: actions/checkout@v6
- run: shellcheck -S warning scripts/termux-backup/termux-backup scripts/termux-backup/lib/*.sh scripts/termux-backup/tests/*.sh
- run: bash scripts/termux-backup/tests/run.sh
```

The existing Gradle job is unchanged.

## 14. Documentation and ledger

- `scripts/termux-backup/README.md`: install, backup, restore, list/verify/delete/prune/status,
  migration walkthrough (backup on old device, copy directory, restore on new device), the
  `termux-setup-storage` requirement, the shared-storage secrecy warning, and the safety-archive
  rollback recipe.
- Root `README.md`: short "Backup and restore" section pointing at the script directory.
- `docs/ledger/outstanding-work.md`: phase-2 UI entry, plus deferred GitHub/Drive backends,
  scheduling, and encryption.
- `docs/ledger/manual-checks.md`: device checks for real `/sdcard` writes after
  `termux-setup-storage`, a real second-device restore, the safety-archive round-trip, and behavior
  when storage permission is revoked.

## 15. Phase 2 outline (not part of this spec's implementation)

A Kotlin settings screen starts `termux-backup <command> --json` through `TermuxService`, renders
progress from the JSON stream, and provisions the script from `assets/` into `$PREFIX/lib` on first
run so a fresh install does not need `curl`. SAF import/export and scheduled backups are added
there. This phase receives its own spec once phase 1 ships.

## 16. Risks and open items

- **Shared storage performance:** writing a multi-hundred-MB archive to `/sdcard` is slow on some
  devices; a `--path` under `$HOME` remains available for speed at the cost of losing the archive on
  an app-data wipe. Documented, not solved in phase 1.
- **`/sdcard` mount differences:** some ROMs expose shared storage differently; unresolved paths
  produce the `termux-setup-storage` hint rather than a generic failure.
- **Package reinstall drift:** `pkg install` may resolve newer versions than the backup date. The
  manifest records the Termux version so the mismatch is at least visible.
- **Encryption:** deferred. Until then, archives with credentials are only as safe as the storage
  root.
