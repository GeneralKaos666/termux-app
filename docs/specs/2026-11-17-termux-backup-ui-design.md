# Termux Backup and Restore — In-App UI Design (Phase 2)

- **Status:** approved for planning (design approved 2026-11-17)
- **Date:** 2026-11-17
- **Scope:** phase 2 — in-app screen that drives the phase-1 `termux-backup` CLI, plus the script
  progress protocol and script provisioning the screen needs, and Storage Access Framework (SAF)
  export/import of backup directories. Scheduled backups remain out of scope.
- **Builds on:** `docs/specs/2026-11-15-termux-backup-restore-design.md` (phase 1, script) and
  `docs/plans/2026-11-15-termux-backup-restore.md`.
- **Ledger entry:** `docs/ledger/outstanding-work.md` §5.5.

## 1. Context

Phase 1 shipped `scripts/termux-backup/` — a bash 5 CLI with `backup`, `restore`, `list`, `verify`,
`delete`, `prune` and `status`. It is not reachable from the app: it lives only in the repository and
must be installed by hand, and every command is typed in a terminal.

The phase-1 spec's §15 named the phase-2 shape: a settings screen that starts
`termux-backup <command> --json` through `TermuxService`, renders progress from the output, and
provisions the script from `assets/` into `$PREFIX/lib`.

Two facts discovered while designing change that outline:

1. `--json` emits **one object at completion**, not a stream. Rendering progress needs an explicit
   progress protocol in the script; parsing the single object cannot show phases.
2. The app ships no `app/src/main/assets` directory, so provisioning starts from zero.

## 2. Intent and success criteria

A user who never opens a terminal can:

- create, verify, delete and prune backups in the default root;
- see what exists, its size, package count and age;
- restore a chosen backup after an explicit confirmation, with a safety archive;
- export a backup directory to a location they pick in the system file picker, and import one back;
- run all of it on a fresh install, offline, with no `curl` and no manual script install.

Success criteria:

- Opening Settings → Advanced → Backup & restore on a fresh install provisions the script and shows
  the backup list without a terminal.
- A backup started from the screen survives the screen closing and reports its phases while it runs.
- Restore cannot start without a dialog naming the backup and both target paths.
- No script phase is shown that the script did not report, and no percentage is invented for a script
  run.
- An exported backup directory imports back byte-identical and verified; a corrupted export is
  refused before it can be listed.
- The CLI keeps its phase-1 behaviour and output when the new flag is not passed.

## 3. Decisions

| Decision | Choice | Why |
|---|---|---|
| Scope | Full CLI surface plus SAF export/import | Backup without restore is half a feature; SAF is how a backup leaves the device; scheduling needs its own design for background limits. |
| SAF shape | `ACTION_OPEN_DOCUMENT_TREE` in both directions, same five-file directory | The on-disk format never changes, and no app-side tar implementation is needed. |
| SAF implementation | Platform `DocumentsContract` behind a small `SafDocumentTree` interface, no new dependency | The app already uses `DocumentsContract`; a fake interface keeps the logic testable. |
| SAF copy lifetime | App-scoped coroutine; temp directory swept on next screen open | Survives the screen closing without adding WorkManager or a second notification. |
| SAF progress | Real bytes and a percentage, per file | Totals are known from `COLUMN_SIZE`; this is the one place where a real bar exists. |
| Placement | One keyed row at the bottom of the Advanced settings screen | Keeps the shipped three-row hub IA; a key makes it reachable from settings search. |
| Execution | Hidden `TermuxSession` created by `TermuxService`, not in the tab list | Reuses session lifetime, shell environment and the existing foreground-service notification, without exposing a terminal. |
| Progress | Script appends JSONL phase events to a file; the screen polls it | Honest phases, attach-after-reopen, no fake byte counts. |
| Script provisioning | Copy APK assets into `$PREFIX/lib/termux-backup` when missing or version differs; "Reinstall script" always overwrites | Offline, versioned, recoverable after a broken copy. |
| Asset source of truth | `scripts/termux-backup/`; Gradle syncs it into a generated assets dir | One canonical copy, no checked-in duplicate that can drift. |
| Restore confirmation | In-app destructive dialog; `--yes` passed to the CLI | The CLI's tty prompt cannot work from the app; confirmation responsibility moves to the UI. |
| Safety archive | On by default, visible toggle, warning when off | Preserves phase-1 rollback semantics. |
| Package reinstall | On by default, visible toggle | Preserves phase-1 restore meaning; the toggle is for offline restores. |
| Post-restore | Prompt "Restart app"; reload properties; never self-kill | The app's in-memory state is stale after files change; killing the process is the user's call. |
| Concurrency | One run at a time, enforced by a lock file | Two runs would share a progress file and race the same backup root. |
| Architecture mismatch | Refuse; `--force` is not exposed | A cross-architecture restore is destructive nonsense; the CLI keeps the escape hatch. |
| Secrets | Included by default (CLI parity), switch to exclude, persistent warning | UI should not silently change what phase 1 backs up. |

## 4. Non-goals

- Scheduled, boot-triggered or background-unattended backups.
- Cloud backends (GitHub, Google Drive, rclone).
- Encryption or password-protected archives.
- Differential or incremental backups.
- Selective per-package restore.
- Editing the CLI's `manifest.json` format or its exit-code contract.
- Changing any `:terminal-emulator`, `:terminal-view` or published `:termux-shared` API.

## 5. Architecture

All new code lives in `:app`. No new Gradle module. Existing layering rules apply
(`LayeringTest`): `domain` imports no Android type, `data` may use Android APIs, `ui` talks to
`domain` and `data`.

```
app/src/main/java/com/termux/app/
  domain/backup/
    BackupCommand.kt          sealed command model -> argv
    BackupProgress.kt         JSONL event model, parser, run state machine
    BackupSummary.kt          parser for list / status / backup result objects
    BackupDefaults.kt         root path, config paths, poll interval, phase labels
    SafBackupName.kt          import/export name derivation and sanitising
    SafBackupManifest.kt      the five-file set, sums parsing
  data/backup/
    BackupScriptProvisioner.kt   assets -> $PREFIX/lib + symlink + version marker
    BackupRunner.kt              lock, session start/stop, progress tail, state flow
    BackupSessionClient.kt       no-op TerminalSessionClient for the hidden session
    SafDocumentTree.kt           interface over DocumentsContract; real + fake impls
    SafBackupTransfer.kt         export/import copy with hashing and temp-then-rename
    BackupTransferCoordinator.kt  app-scoped owner of the current transfer
  ui/backup/
    BackupActivity.kt         hosts the Compose screen
    BackupScreen.kt           stateless composables by state
    BackupViewModel.kt        state holder, actions, polling lifecycle
    BackupState.kt            screen state types
```

Dependencies:

- `domain/backup` — Kotlin + `org.json` only. No Android type; parser tests run under Robolectric
  because the JVM `org.json` stub throws.
- `data/backup` — may read assets, call `TermuxService`, use `android.system.Os` and
  `DocumentsContract`.
- `ui/backup` — Compose, ViewModel, `LifecycleService`-free; polls while `STARTED`.
- The settings fragment only starts `BackupActivity`; it holds no backup logic.

### 5.1 Unit boundaries

- `BackupCommand` answers "what argv do I build for this intent". It never executes.
- `BackupProgress` answers "what does this event history mean right now". It never reads files.
- `BackupRunner` answers "start, attach, cancel, and report one run". It owns all I/O and platform
  calls, and exposes a single `StateFlow<BackupProgress.State>`.
- `BackupScriptProvisioner` answers "is the bundled script installed and current". It never runs it.
- `SafBackupTransfer` answers "copy this backup out, or that one in, and prove it". It never starts a
  session.
- `BackupTransferCoordinator` answers "is a copy already running, and what is its progress". It
  owns the only app-scoped coroutine.
- `BackupViewModel` maps runner and transfer state to screen state and invokes one operation at a
  time.

## 6. Script progress protocol (phase-1 script change)

`TOOL_VERSION` becomes `1.1.0`. `FORMAT_VERSION` stays `1`; the archive layout is unchanged.

New global flag: `--progress-file PATH` (also accepted by every subcommand). When present:

- the file is truncated when the run starts;
- one JSON object per line is appended, flushed per line;
- human output, `--json` and `--quiet` behaviour are unchanged;
- no flag means no file and no new behaviour.

Event shapes:

```json
{"ts":"2026-11-17T10:15:30Z","event":"run_start","command":"backup","pid":12345}
{"ts":"2026-11-17T10:15:31Z","event":"phase","name":"archiving_home"}
{"ts":"2026-11-17T10:16:02Z","event":"run_end","exit_code":0}
```

Rules:

- `ts` is UTC ISO-8601 with `Z`, same clock as `backup.log`.
- `command` is the subcommand name (`backup`, `restore`, `list`, `verify`, `delete`, `prune`,
  `status`).
- `phase` names are a closed set: `verifying`, `scanning`, `safety`, `collect_packages`,
  `archiving_home`, `archiving_etc`, `checksums`, `prune`, `extracting_etc`, `extracting_home`,
  `packages`. `collect_packages` is the `dpkg --get-selections` pass used by both a backup and a
  safety archive; `packages` is the post-restore reinstall.
- Exactly one `run_start` first and exactly one `run_end` last, including on `die` paths.
- A failure writes `run_end` with the CLI's exit code before exiting.
- Events are written even when the process is later killed; the UI treats a missing `run_end` as an
  interrupted run.

Implementation constraint: the dispatcher must trap `EXIT` to emit `run_end` exactly once; library
functions receive the emit helper rather than opening the file themselves.

Tests (shell suite): event order for `backup` and `restore`, `run_end` on a forced failure, no file
when the flag is absent, and `--json` stdout still parseable when the flag is present.

## 7. Asset pipeline

`scripts/termux-backup/` remains the only source. A Gradle `Sync` task copies
`termux-backup`, `lib/*.sh` into `app/build/generated/backupAssets/termux-backup/` and that
directory is added to the `main` asset source set, with the assets merge depending on the sync task.
`tests/`, `install.sh` and `README.md` are not packaged.

The asset tree contains a generated `VERSION` file holding `TOOL_VERSION`, read by the app with the
rest of the assets. The task fails the build if the script's `TOOL_VERSION` cannot be found, so an
APK cannot ship an unversioned copy.

Verification risk: AGP 9 asset source registration. The first plan task proves the generated
directory is packaged (`unzip -l` on the debug APK) before anything depends on it.

## 8. Provisioning

Target layout, matching `install.sh`:

```
$PREFIX/lib/termux-backup/termux-backup      (0755)
$PREFIX/lib/termux-backup/lib/*.sh
$PREFIX/lib/termux-backup/.app-version       (<TOOL_VERSION>)
$PREFIX/bin/termux-backup -> ../lib/termux-backup/termux-backup
```

Rules:

- Provision on screen open when the directory is missing, `.app-version` is missing or differs from
  the bundled version, or the dispatcher is not executable.
- "Reinstall script" always overwrites, and says it replaces local edits.
- Copy to a temporary directory and rename, so a failure never leaves a half-written script.
- Failure (read-only prefix, no space) is shown as a message with the failing path; the screen still
  renders, with actions disabled and a Retry button.
- The symlink is refreshed with `ln -sfn`; an existing regular file at `$PREFIX/bin/termux-backup` is
  replaced only by "Reinstall script", never silently.
- No provisioning runs at app start.

## 9. Execution and run lifecycle

### 9.1 Starting

`TermuxService.createBackupSession(executionCommand)` mirrors `createFloatSession`: it creates a
`TermuxSession` with a supplied `TerminalSessionClient`, does not add it to
`mShellManager.mTermuxSessions`, and does not touch activity styling. The existing service
notification remains the only lifecycle owner.

Command shape:

```
<TERMUX_BIN_PREFIX_DIR_PATH>/bash <PREFIX>/lib/termux-backup/termux-backup <subcommand> <args...>
  --json --progress-file <progress path>
```

`--path` is never passed: the CLI resolves its own root from its config file, environment or default,
and the screen shows the resolved root from `status`. Restore adds `--yes` and, per toggles,
`--no-packages` / `--no-safety`. `--json` is required for the result summary; progress comes from the
file.

### 9.2 Lock and attach

`BackupRunner` writes `~/.config/termux-backup/run.lock`:

```json
{"command":"backup","pid":12345,"started_at":"2026-11-17T10:15:30Z"}
```

- The lock is written after the session starts, using the session's PID.
- Opening the screen with a lock present attaches: the screen tails `progress.jsonl` and renders the
  running phases.
- A lock whose PID no longer exists (`Os.kill(pid, 0)` raises `ESRCH`) is stale; the runner clears it
  and shows the last progress file as an interrupted run.
- A second start request while a run is live shows "A backup is already running" instead of
  starting.
- The lock is removed on normal exit, on cancel, and when detected stale.

### 9.3 Cancel

Cancel calls `finishIfRunning()` on the session. The script dies by signal; the progress file has no
`run_end`, so the UI shows Cancelled, not Failed. A partial backup directory cannot be listed,
because the script writes into `.tmp.$$` and moves only on success.

### 9.4 Progress polling

The ViewModel polls `progress.jsonl` every 500 ms while the screen is started, keyed on file size so
unchanged files are not re-read. It always polls immediately on open and once on resume. Polling and
the lock check run on `Dispatchers.IO`. Leaving the screen stops polling only; the run continues.

## 10. Screen specification

Entry: Settings → Advanced → Backup & restore (`app:key="backup_restore"`), opening
`BackupActivity`. Title: "Backup & restore".

### 10.1 Header card

- Root path, with the default `/sdcard/termux-backups` when no override is set.
- State line: writable, storage access, free space, backup count.
- When storage access is false: one action, "Grant storage access", starting the existing app
  permission flow, plus the `termux-setup-storage` hint when the ROM needs it.
- A persistent warning line when the root is under `/sdcard`: archives contain shell history,
  dotfiles and possibly credentials.

### 10.2 Create backup

- Label field (optional, `[A-Za-z0-9._-]+`, validated before start).
- Compression: `gz` (default), `bz2`, `xz`.
- Keep: number, default 5, `0` = never prune.
- Switches: "Back up $HOME" (on), "Back up $PREFIX/etc" (on), "Exclude secrets" (off).
- Button "Back up now", disabled while a run is active or provisioning failed.

### 10.3 Backups list

Each entry: name, kind badge (`Backup` / `Safety`), created time, size, package count. Actions per
entry:

- **Verify** — runs `verify --name`; result shown inline as Verified / Corrupt.
- **Restore** — opens the restore dialog (§11).
- **Export…** — opens the system tree picker and copies the directory (§17).
- **Delete** — destructive dialog naming the entry, then `delete --name --yes`.

The screen header also offers **Import backup…**, which opens the tree picker on a backup directory
and copies it into the current root (§17). Export and Import are available for safety entries too.

Safety entries are labelled and never offered for automatic pruning.

### 10.4 Prune

Action "Prune backups" with a keep count (default from `status`), destructive confirmation, runs
`prune --keep N`. Safety archives are never counted or removed (phase-1 behaviour, unchanged).

### 10.5 Progress and result

While a run is active: a phase stepper of the phases valid for that command, the elapsed time, and
Cancel. Phases not reached yet are dimmed; the current phase is highlighted; completed phases are
checked. No percentage and no time estimate.

SAF transfers are the one exception: because the file sizes are known, they show the current file
name, bytes copied over total, and a real percentage bar. Script runs still never show a percentage.

When the run ends:

- success: summary from the `--json` object (name, size, package count, or restored backup name plus
  safety archive name) and the actions valid afterwards (Open list, Restart app after restore);
- failure: the CLI's exit code and the last human error line captured from the progress/session
  output, with Retry;
- cancelled: "Cancelled" and the last phase reached.

### 10.6 Empty and error states

- No backups: explanatory text plus the create form.
- Provisioning failed: the failure message, Retry, and a disabled action set.
- Script missing after provisioning: same as provisioning failure.
- Concurrent run: banner naming the running command and its phase.

## 11. Restore flow

1. The dialog names the backup, its created time and size, and both targets (`$HOME`,
   `$PREFIX/etc`), and states that files will be overwritten and nothing deleted.
2. Toggles: "Create a safety backup first" (on; warning text when off) and "Reinstall missing
   packages" (on).
3. Primary button "Restore"; secondary "Cancel". The button is destructive-styled.
4. The command runs with `--yes` and the toggles mapped to `--no-safety` / `--no-packages`.
5. On success the result card shows the restored name, the safety archive name when created, and a
   "Restart app" action. A `TERMUX_ACTIVITY.ACTION_RELOAD_STYLE` broadcast is sent best-effort so a
   live `TermuxActivity` reloads its properties; because that broadcast is dropped while the activity
   is stopped, the restart prompt stays the authoritative step.
6. Exit 3 (files restored, packages failed) is shown as a partial success: files restored, package
   list on screen, safety archive named.

The UI never passes `--force` and never offers a cross-architecture override.

## 12. Error handling

- Every failure path renders a message with the CLI's error line verbatim; nothing is swallowed into
  a generic "Something went wrong".
- Provisioning errors name the failing path.
- A malformed progress line is skipped; one bad line never stops the stepper.
- A progress file with `run_start` but no `run_end`, and no live PID, renders as Interrupted.
- A `list`/`status` parse failure shows the raw error and a Retry, and leaves previous data visible.
- A SAF provider error is shown verbatim with the failing file name; a transfer never reports success
  unless every copied file matched its checksum.
- An import that fails validation leaves no destination directory; an export that fails removes the
  directory it created in the picked tree.
- The screen never blocks the main thread: all file reads, process checks and session calls that do
  I/O run on `Dispatchers.IO`.

## 13. Security

- Restore is guarded by a dialog; `--yes` is only ever passed by that confirmed path.
- The shared-storage secrecy warning is always visible when the root is on `/sdcard`.
- The screen never stores credentials, never uploads, and never adds a network path.
- Script provisioning writes only under `$PREFIX/lib/termux-backup` and the one symlink.
- The hidden session runs the bundled script path, never a user-supplied path or argument string;
  arguments are passed as an argv array, not through a shell string.
- Imported SAF content is untrusted: it is hashed against `SHA256SUMS`, its member names are
  validated against traversal, and it is not listable until verification passes. `manifest.json` is
  parsed only for `format_version` and `created_at`, never executed.
- Export writes only into the tree the user picked and only with names from `SHA256SUMS` plus the
  three fixed metadata files.

## 14. Testing

### 14.1 Unit tests (`app/src/test/java/com/termux/app/...`)

The parser and command tests use `org.json`, whose JVM stub throws, so those classes run under
Robolectric like the existing `:app` tests. Everything else is plain JUnit.

- `BackupCommandTest` — argv per command; `--yes` only for restore; toggle mapping; label validation;
  custom root only when set; no shell-string interpolation.
- `BackupProgressTest` — event ordering, unknown phase ignored, partial trailing line, corrupt line,
  missing `run_end` = interrupted, `exit_code` mapping, cancel detection.
- `BackupSummaryTest` — `list` and `status` objects, escaped names, empty list, extra keys tolerated,
  malformed JSON error.
- `BackupLockTest` — stale PID detection (injected process probe), live PID, missing lock.
- `BackupViewModelTest` — start blocked while running, attach on open, poll stops on cleared.
- `SafBackupNameTest` — sanitising, pattern-preserving names, manifest-derived fallback, collision
  suffixes.
- `SafBackupManifestTest` — sums parsing, duplicate and traversal names rejected, missing file
  listed, format-version guard.
- `SafBackupTransferTest` (fake `SafDocumentTree`) — export copies the exact set and verifies
  hashes; hash mismatch deletes the created directory; import round-trips, rejects a corrupted file,
  refuses low free space, sweeps a leftover temp directory, cancel deletes partial output.
- `BackupTransferCoordinatorTest` — one transfer at a time, refusal while the run lock exists.

### 14.2 Robolectric tests

- `BackupScriptProvisionerTest` — fresh copy, version match skips, version mismatch copies, symlink
  refresh, temp-then-rename, read-only prefix failure message.
- `SettingsIndexBackupRowTest` or an addition to the existing settings-search tests — the
  `backup_restore` row is indexed and a query matching its title keeps it.

### 14.3 Shell tests

Progress-event cases from §6 in `scripts/termux-backup/tests/`; `shellcheck -S warning` stays clean.

### 14.4 Device checks

New section in `docs/ledger/manual-checks.md` covering: fresh-install provisioning and the row
appearing in settings search; backup with the screen closed and reopened (attach); cancel; verify on
a corrupted archive; restore dialog, safety archive and restart prompt; storage permission revoked;
second device restore; SAF export into a Downloads tree and import back; a corrupted SAF import
refused; export cancelled with its directory removed.

## 15. CI

No new job. `./gradlew test` covers §14.1–14.2; the existing `shell` job covers §14.3.
`:app:assembleDebug` must still pass with the generated assets, and the packaged APK must contain
`assets/termux-backup/termux-backup` and `assets/termux-backup/VERSION`.

## 16. Documentation and ledger

- `docs/ledger/outstanding-work.md` §5.5: phase 2 recorded as shipped, with SAF export/import landed
  and scheduling still deferred.
- `scripts/termux-backup/README.md`: document `--progress-file` as an automation flag, note that the
  app uses it, and that the in-app screen exists.
- Root `README.md`: extend the "Backup and restore" section to mention the Settings screen and SAF
  export/import.
- `docs/ledger/manual-checks.md`: the device section from §14.4.

## 17. SAF export and import

### 17.1 Picker and document access

- Export starts from a backup entry and calls `ACTION_OPEN_DOCUMENT_TREE`; the user picks the tree
  to write into.
- Import calls `ACTION_OPEN_DOCUMENT_TREE` and the user picks the backup directory itself.
- No persistable URI permission is taken: the URI is used for the copy and then dropped. A process
  restart aborts the transfer (see §17.4).
- Access goes through a small `SafDocumentTree` interface: `children()`, `createDirectory(name)`,
  `createFile(parent, name)`, `openInput(document)`, `openOutput(document)`, `size(document)`,
  `delete(document)`. The real implementation uses `DocumentsContract`
  (`buildChildDocumentsUriUsingTree`, `createDocument`, `openInputStream`/`openOutputStream`,
  `deleteDocument`, `getTreeDocumentId`); tests use a fake. No new dependency.

### 17.2 Export

1. Run only when the entry is listable (a directory with `SHA256SUMS`) and no script run or transfer
   is active.
2. Export set: every file named in `SHA256SUMS`, plus `SHA256SUMS`, `manifest.json` when present and
   `packages.txt` when present. Files the sums do not name are not exported.
3. Create a directory named after the backup in the picked tree. If a child with that name already
   exists, refuse with a message rather than overwriting.
4. Copy in a fixed order with `SHA256SUMS` last, mirroring the script's rule. Each file is hashed
   while it is copied and compared with `SHA256SUMS`; a mismatch aborts and deletes the directory
   this export created.
5. Progress: current file name, bytes copied over total (the sum of `COLUMN_SIZE` values). A file
   whose provider reports no size streams without contributing to the total, and the bar stays
   indeterminate for that file.
6. After the copy, re-list the created directory and confirm the export set is present. Report the
   directory name the provider actually assigned, which may differ from the requested name.

### 17.3 Import

1. Enumerate the picked tree's direct children only; never recurse. Require `SHA256SUMS` and every
   file it names.
2. Parse `SHA256SUMS` as `<64 hex>  <name>`. Reject empty names, names containing `/`, `..`
   components and duplicate names: this is the path-traversal guard, because SAF display names are
   user-controlled.
3. Destination name: the tree's display name sanitised to `[A-Za-z0-9._-]` (other characters become
   `-`, surrounding dots and dashes trimmed). If the result does not start with `termux_backup_` or
   `safety_pre_restore_`, derive `termux_backup_<created_at>` from the manifest when it reads
   `yyyyMMdd_HHmmss`, else `termux_backup_<now>`. A name collision gets `-2`, `-3`, … appended.
4. Preflight: free space at the root at least `1.05 × total size + 16 MiB`; otherwise refuse and
   show both figures.
5. Copy into `<root>/.tmp-import-<pid>`, hashing while copying. Every file must match
   `SHA256SUMS`; on any mismatch or I/O error the temp directory is deleted and nothing is listed.
6. When `manifest.json` is readable, reject a `format_version` other than `1`. Then rename the temp
   directory to the destination name and refresh the list.
7. There is no size cap; progress is per file plus bytes over total.

### 17.4 Cancellation and failure

- Cancel deletes the export directory this transfer created, or the import temp directory, then
  reports Cancelled.
- Process death during an import leaves a `.tmp-import-*` directory; the coordinator sweeps every
  such directory on the next screen open. An interrupted export cannot be swept inside a foreign
  tree; because the coordinator records the created document names in its state file, the next open
  reports it as an interrupted export and offers to delete those documents.
- Provider errors are reported verbatim with the failing file name; nothing is retried silently.
- A SAF transfer and a script run are mutually exclusive. The coordinator refuses to start while the
  run lock exists, and `BackupRunner` refuses to start while a transfer is active.

### 17.5 Format guarantees

- No on-disk format change: an exported directory is the same five files the CLI produces, and an
  imported one is an ordinary list entry that can be verified, restored, pruned or exported again.
- Only data files are copied; no executable mode needs preserving.
- The script and the app never share a file mid-write: import renames only after all sums match.

## 18. Risks and open items

- **Generated assets under AGP 9** — the only build-shape risk; proven in the first task with an
  `unzip -l` check.
- **Restore while sessions run** — a running shell may write files during extraction. The UI warns;
  the script's merge semantics keep it non-destructive. No session is auto-closed.
- **`/sdcard` performance** — unchanged from phase 1; a slow run shows phases, not progress, by
  design.
- **Script edited by the user** — the version marker would not change, so no overwrite happens;
  "Reinstall script" is the explicit escape hatch. Documented in the confirmation text.
- **`org.json` and unit tests** — the Android implementation is required; the JVM stub throws, so
  parser tests use Robolectric. No new dependency.
- **SAF provider variance** — tree pickers exist on every supported API level, but providers differ:
  some report no size, some rename documents on creation, some are cloud-backed and slow. The spec
  handles each explicitly (indeterminate per-file bar, report the assigned name, verbatim provider
  errors); no provider is special-cased.
- **Interrupted export** — a foreign tree cannot be swept automatically. The coordinator's state
  file records what it created so the next open can offer deletion, but a process death between
  creating the directory and recording it leaves an orphan the user must remove.

## 19. Acceptance criteria

1. Fresh install, no terminal: Settings → Advanced → Backup & restore provisions the script and
   lists zero backups.
2. "Back up now" produces a listed, verifiable backup; closing and reopening the screen during the
   run attaches to it.
3. Cancel stops the run and reports Cancelled; no partial backup is listed.
4. Verify reports Corrupt after an archive is truncated.
5. Restore requires the dialog, creates a safety archive by default, and offers Restart app.
6. Delete and Prune require confirmations; safety archives survive Prune.
7. Settings search finds "Backup & restore".
8. A backup exports to a picked tree, imports back into a fresh root, and verifies there; a
   corrupted export is refused on import and leaves no listed entry.
9. Cancelling an export or import removes what that transfer created.
10. `./gradlew test` and the `shell` CI job are green; `--progress-file` absent leaves CLI output
    unchanged.
11. No `:terminal-emulator`, `:terminal-view` or published `:termux-shared` API changed.
