# Termux Backup and Restore In-App UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Settings screen that drives the phase-1 `termux-backup` CLI (backup, restore, list, verify, delete, prune, status), plus the script progress protocol, script provisioning from the APK, and SAF export/import of backup directories.

**Architecture:** New code lives in `:app` under `domain/backup` (pure command/progress/summary/name logic), `data/backup` (provisioning, hidden session runner, SAF transfer, coordinator) and `ui/backup` (Compose activity + ViewModel). The script gains one global `--progress-file` flag that appends JSONL events; the app polls it. A Gradle `Sync` task packages `scripts/termux-backup/` into APK assets.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.preference`, `org.json`, `android.provider.DocumentsContract`, bash 5, Gradle 9 / AGP 9, Robolectric, JUnit.

**Spec:** `docs/specs/2026-11-17-termux-backup-ui-design.md`

## Global Constraints

- `TOOL_VERSION` becomes `1.1.0`; `FORMAT_VERSION` stays `1`. Archive layout unchanged.
- Progress file path used by the app: `<TERMUX_HOME_DIR_PATH>/.config/termux-backup/progress.jsonl`. Lock: `<TERMUX_HOME_DIR_PATH>/.config/termux-backup/run.lock`. Transfer state: `<TERMUX_HOME_DIR_PATH>/.config/termux-backup/transfer.json`.
- Script install dir: `<TERMUX_LIB_PREFIX_DIR_PATH>/termux-backup`; symlink `<TERMUX_BIN_PREFIX_DIR_PATH>/termux-backup`; version marker file inside the install dir: `.app-version`.
- Settings key: `backup_restore`; strings `termux_backup_preferences_title`, `termux_backup_preferences_summary`, `title_activity_backup`.
- No new third-party dependency. No change to `:terminal-emulator`, `:terminal-view` or the published `:termux-shared` API.
- Package identity, `TERMUX_PACKAGE_NAME` and companion-app integration unchanged.
- Kotlin 4-space indent, LF, final newline. Commits use Conventional Commits with capitalized types: `Added(app): ...`, `Changed(scripts): ...`.
- Script exit codes unchanged: `0` success, `1` usage/unknown name, `2` verification/preflight failure, `3` files restored but packages failed.
- `--json` writes exactly one object to stdout; human messages go to stderr. `--json` and `--quiet` stay mutually exclusive.
- `./gradlew test` must stay green; the `shell` CI job must stay green.

## Review Focus

- Poll reads `progress.jsonl` while the script is appending: a half-written trailing line must be ignored, never crash the stepper. Covered by `BackupProgressTest`.
- `$PREFIX` read-only or the script copy half-written: the screen must render with a message and disabled actions, never crash. Covered by `BackupScriptProvisionerTest`.
- SAF provider that reports no size or auto-renames a created directory: the copy must not divide by zero or lose the destination. Covered by `SafBackupTransferTest`.
- Stale lock whose PID was reused by another process: the screen must not show a phantom running backup. Covered by `BackupRunnerTest`.
- Restore exit code `3`: files restored, packages failed; the UI must report partial success, not failure. Covered by `BackupViewModelTest`.

---

### Task 1: Script progress protocol

**Files:**
- Modify: `scripts/termux-backup/termux-backup`
- Modify: `scripts/termux-backup/lib/common.sh`
- Modify: `scripts/termux-backup/lib/archive.sh`
- Modify: `scripts/termux-backup/lib/restore.sh`
- Modify: `scripts/termux-backup/lib/storage.sh`
- Test: `scripts/termux-backup/tests/progress_test.sh`

**Interfaces:**
- Consumes: existing `die`, `log_run`, `backup_create_body`, `safety_create`, `restore_extract`, `packages_install`.
- Produces: global `--progress-file PATH`, `PROGRESS_FILE`, `progress_init <command>`, `progress_phase <name>`, `progress_end <code>`, and phase names `verifying`, `scanning`, `safety`, `collect_packages`, `archiving_home`, `archiving_etc`, `checksums`, `prune`, `extracting_etc`, `extracting_home`, `packages`.

- [ ] **Step 1: Write the failing tests**

```bash
# tests/progress_test.sh
test_progress_records_backup_events_in_order() {
    setup_sandbox
    echo hi > "$HOME/file.txt"
    bash "$SCRIPT" backup --progress-file "$SANDBOX/progress.jsonl"
    local log="$SANDBOX/progress.jsonl"
    assert_contains '"event":"run_start"' "$(sed -n 1p "$log")" "run_start first"
    assert_contains '"command":"backup"' "$(sed -n 1p "$log")" "command"
    assert_contains '"event":"phase","name":"archiving_home"' "$(cat "$log")" "home phase"
    assert_contains '"event":"phase","name":"checksums"' "$(cat "$log")" "checksums phase"
    assert_contains '"event":"run_end","exit_code":0' "$(tail -n 1 "$log")" "run_end last"
    teardown_sandbox
}

test_progress_records_failure_exit_code() {
    setup_sandbox
    TAR_FAIL=1 bash "$SCRIPT" backup --progress-file "$SANDBOX/progress.jsonl" || true
    assert_contains '"event":"run_end","exit_code":2' "$(tail -n 1 "$SANDBOX/progress.jsonl")" "failure code"
    teardown_sandbox
}

test_progress_flag_absent_writes_no_file() {
    setup_sandbox
    bash "$SCRIPT" status >/dev/null 2>&1
    assert_eq "" "$(find "$SANDBOX" -name '*.jsonl' -print -quit)" "unexpected progress file"
    teardown_sandbox
}

test_progress_does_not_pollute_json_stdout() {
    setup_sandbox
    local out
    out=$(bash "$SCRIPT" status --json --progress-file "$SANDBOX/progress.jsonl")
    assert_contains '"tool_version":"1.1.0"' "$out" "json stdout"
    assert_not_contains '"event"' "$out" "events stay out of stdout"
    teardown_sandbox
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash scripts/termux-backup/tests/run.sh`
Expected: FAIL on the four new cases (unknown option `--progress-file`, no file written).

- [ ] **Step 3: Implement the protocol**

In `lib/common.sh`: bump `TOOL_VERSION="1.1.0"`; add `PROGRESS_FILE=""`, `progress_init`, `progress_phase`, `progress_end`, each a no-op when `PROGRESS_FILE` is empty. `progress_init` truncates the file, writes `run_start` with `date -u +%Y-%m-%dT%H:%M:%SZ` and `$$`; `progress_phase` appends `{"ts":...,"event":"phase","name":"<name>"}`; `progress_end` appends `{"ts":...,"event":"run_end","exit_code":N}`. Use `>>` with a single `printf` per event.

In the dispatcher: extend the global flag scan to strip `--progress-file PATH` (and `--progress-file=PATH`) from the argument array, reject a missing value with exit 1, then call `progress_init "$sub"` before dispatch. Replace the `EXIT` trap with one that emits `progress_end "$rc"` before `log_run` and exits with `$rc`.

Emit phases at exactly these points: `archive.sh` `cmd_backup` — `collect_packages` before `packages_write`, `archiving_home`/`archiving_etc` inside `backup_create_body` around each `archive_create_*`, `checksums` around `sums_write`, `prune` around `backup_prune`; `restore.sh` `cmd_restore` — `verifying` before `sums_verify`, `scanning` before the archive scan, `safety` around `safety_create`, `extracting_etc`/`extracting_home` inside `restore_extract`, `packages` around `packages_install`; `storage.sh` `cmd_verify` — `verifying`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash scripts/termux-backup/tests/run.sh && shellcheck -S warning scripts/termux-backup/termux-backup scripts/termux-backup/lib/*.sh scripts/termux-backup/tests/*.sh`
Expected: all cases PASS, shellcheck exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/termux-backup
git commit -m "Added(scripts): Emit JSONL progress events from termux-backup"
```

---

### Task 2: Package the script as APK assets

**Files:**
- Modify: `app/build.gradle`
- Verify: generated `app/build/generated/backupAssets/termux-backup/`

**Interfaces:**
- Produces: assets rooted at `termux-backup/` containing `termux-backup`, `lib/*.sh` and `VERSION` (`1.1.0`), readable at runtime with `context.assets.open("termux-backup/VERSION")`.

- [ ] **Step 1: Add the Gradle sync task**

In `app/build.gradle`, register a `Sync` task named `syncBackupAssets` that copies `termux-backup` and `lib/` from `rootProject.file('scripts/termux-backup')` into `layout.buildDirectory.dir('generated/backupAssets/termux-backup')`, and reads `TOOL_VERSION` from `scripts/termux-backup/lib/common.sh` with a regex, failing the build when it is absent. Write the captured value to `VERSION` in the same output directory.

Register the output as an asset source (`android.sourceSets.main.assets.srcDir(...)`) and make `preBuild` depend on the task.

- [ ] **Step 2: Build and verify packaging**

Run: `./gradlew :app:assembleDebug`
Then: `unzip -l app/build/outputs/apk/debug/*.apk | grep 'assets/termux-backup'`
Expected: entries for `assets/termux-backup/termux-backup`, `assets/termux-backup/VERSION` and `assets/termux-backup/lib/common.sh`. If AGP 9 ignores the extra assets dir, fall back to copying with `Sync` into `app/src/main/assets/termux-backup` before `mergeAssets` and add that generated path to `.gitignore`; record the chosen wiring in the commit message.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle .gitignore
git commit -m "Added(app): Package termux-backup in APK assets"
```

---

### Task 3: Command model

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/backup/BackupCommand.kt`
- Test: `app/src/test/java/com/termux/app/domain/backup/BackupCommandTest.kt`

**Interfaces:**
- Produces: `sealed interface BackupCommand` with `val subcommand: String` and `fun argv(scriptPath: String, progressFile: String): List<String>`; cases `Backup(label: String?, compression: String, keep: Int?, excludeSecrets: Boolean, includeHome: Boolean, includeConfig: Boolean)`, `Restore(name: String, safety: Boolean, packages: Boolean)`, `data object ListBackups`, `data object Status`, `Verify(name: String)`, `Delete(name: String)`, `Prune(keep: Int)`; plus `fun labelError(label: String): String?` in `companion object`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `backup argv carries flags and never shell-quotes`() {
    val argv = BackupCommand.Backup("pre-upgrade", "xz", 3, true, true, false)
        .argv("/p/lib/termux-backup/termux-backup", "/h/progress.jsonl")
    assertEquals(
        listOf(
            "/p/lib/termux-backup/termux-backup", "backup",
            "--label", "pre-upgrade", "--compression", "xz", "--keep", "3",
            "--exclude-secrets", "--no-config",
            "--json", "--progress-file", "/h/progress.jsonl",
        ),
        argv,
    )
}

@Test
fun `restore argv always confirms and maps toggles`() {
    val argv = BackupCommand.Restore("termux_backup_20260101_000000", safety = false, packages = false)
        .argv("/p/t", "/h/p.jsonl")
    assertTrue("--yes" in argv)
    assertTrue("--no-safety" in argv)
    assertTrue("--no-packages" in argv)
}

@Test
fun `delete and verify differ on yes`() {
    assertTrue(BackupCommand.Delete("x").argv("/p/t", "/h/p").contains("--yes"))
    assertFalse(BackupCommand.Verify("x").argv("/p/t", "/h/p").contains("--yes"))
}

@Test
fun `label validation rejects separators`() {
    assertNull(BackupCommand.labelError("pre-upgrade"))
    assertNotNull(BackupCommand.labelError("bad/label"))
    assertNotNull(BackupCommand.labelError("bad label"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.BackupCommandTest"`
Expected: FAIL, unresolved reference `BackupCommand`.

- [ ] **Step 3: Implement the sealed command and its argv builder**

`--json` and `--progress-file <path>` are appended by `argv` for every case, in that order. `--path` is never emitted. `Backup` emits `--no-home`/`--no-config` only when the corresponding include flag is false, and `--exclude-secrets` only when true.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.BackupCommandTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/backup/BackupCommand.kt \
        app/src/test/java/com/termux/app/domain/backup/BackupCommandTest.kt
git commit -m "Added(app): Model termux-backup commands and argv"
```

---

### Task 4: Progress parser

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/backup/BackupProgress.kt`
- Test: `app/src/test/java/com/termux/app/domain/backup/BackupProgressTest.kt`

**Interfaces:**
- Produces: `enum class BackupPhase(val wire: String)` with `companion object { fun fromWire(value: String): BackupPhase? }`; `sealed interface BackupRunState` with `data object Idle`, `data class Running(val command: String, val phase: BackupPhase?, val startedAt: String?)`, `data class Finished(val command: String, val exitCode: Int, val ok: Boolean)`, `data object Interrupted`; `data class BackupProgress(val state: BackupRunState, val phasesSeen: List<BackupPhase>, val startedAt: String?)`; `object BackupProgressParser { fun parse(text: String): BackupProgress; fun parseFile(file: File): BackupProgress }`.

- [ ] **Step 1: Write the failing tests** (Robolectric, because `org.json`)

```kotlin
@Test fun `empty file is idle`() { assertEquals(BackupRunState.Idle, BackupProgressParser.parse("").state) }

@Test fun `running reports the last phase`() {
    val text = """
        {"ts":"t","event":"run_start","command":"backup","pid":1}
        {"ts":"t","event":"phase","name":"collect_packages"}
        {"ts":"t","event":"phase","name":"archiving_home"}
    """.trimIndent()
    val progress = BackupProgressParser.parse(text)
    assertEquals(BackupRunState.Running("backup", BackupPhase.ARCHIVING_HOME, "t"), progress.state)
    assertEquals(listOf(BackupPhase.COLLECT_PACKAGES, BackupPhase.ARCHIVING_HOME), progress.phasesSeen)
}

@Test fun `partial trailing line is ignored`() {
    val text = """{"event":"run_start","command":"backup","ts":"t"}{"event":"pha"""
    val progress = BackupProgressParser.parse(text)
    assertTrue(progress.state is BackupRunState.Running)
}

@Test fun `run end wins and maps exit codes`() {
    val ok = BackupProgressParser.parse("""{"event":"run_start","command":"restore","ts":"t"}
        {"event":"run_end","exit_code":0}""")
    assertEquals(BackupRunState.Finished("restore", 0, true), ok.state)
    val failed = BackupProgressParser.parse("""{"event":"run_start","command":"backup","ts":"t"}
        {"event":"run_end","exit_code":2}""")
    assertEquals(BackupRunState.Finished("backup", 2, false), failed.state)
}

@Test fun `run without end keeps the running state`() {
    val progress = BackupProgressParser.parse("""{"event":"run_start","command":"backup","ts":"t"}
        {"event":"phase","name":"archiving_home"}""")
    assertEquals(BackupRunState.Running("backup", BackupPhase.ARCHIVING_HOME, "t"), progress.state)
}

@Test fun `unknown phase and corrupt line are skipped`() {
    val text = "not json\n" + """{"event":"run_start","command":"backup","ts":"t"}
        {"event":"phase","name":"future_phase"}
        {"event":"phase","name":"checksums"}"""
    assertEquals(BackupPhase.CHECKSUMS, (BackupProgressParser.parse(text).state as BackupRunState.Running).phase)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.BackupProgressTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement the parser**

Split on `\n`, skip blank lines, `runCatching { JSONObject(line) }` to drop corrupt lines, ignore unknown `event` and unknown phase names. The last `run_end` wins; JSON `exit_code` defaults to `2` when missing. `phasesSeen` preserves order and drops duplicates.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.BackupProgressTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/backup/BackupProgress.kt \
        app/src/test/java/com/termux/app/domain/backup/BackupProgressTest.kt
git commit -m "Added(app): Parse termux-backup progress events"
```

---

### Task 5: Result model for the CLI JSON

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/backup/BackupSummary.kt`
- Test: `app/src/test/java/com/termux/app/domain/backup/BackupSummaryTest.kt`

**Interfaces:**
- Produces: `enum class BackupKind { BACKUP, SAFETY }`; `data class BackupEntry(val name: String, val kind: BackupKind, val createdAt: String, val sizeBytes: Long, val packages: Int)`; `data class BackupList(val root: String, val entries: List<BackupEntry>)`; `data class BackupStatus(val root: String, val writable: Boolean, val storageAccess: Boolean, val backups: Int, val compression: String, val keep: Int, val freeSpaceBytes: Long)`; `data class CreatedBackup(val name: String, val root: String, val sizeBytes: Long, val packages: Int)`; `data class RestoredBackup(val name: String, val root: String, val safety: String?, val packagesInstalled: Int, val packagesFailed: Int)`; `data class VerifiedBackup(val name: String, val valid: Boolean)`; `data class PrunedBackups(val removed: Int, val kept: Int, val keep: Int)`; `class BackupJsonException(message: String) : Exception(message)`; `object BackupJson` with `list`, `status`, `created`, `restored`, `verified`, `deleted`, `pruned`, and `fun lastJsonLine(transcript: String): String`.

- [ ] **Step 1: Write the failing tests** (Robolectric)

```kotlin
@Test fun `list parses backup and safety kinds`() {
    val raw = """{"root":"/r","backups":[
        {"name":"termux_backup_20260101_000000","created_at":"2026-01-01 00:00:00","size":10,"packages":2,"kind":"backup"},
        {"name":"safety_pre_restore_20260102_000000","created_at":"2026-01-02 00:00:00","size":5,"packages":0,"kind":"safety"}]}"""
    val list = BackupJson.list(raw)
    assertEquals("/r", list.root)
    assertEquals(listOf(BackupKind.BACKUP, BackupKind.SAFETY), list.entries.map { it.kind })
}

@Test fun `status parses booleans and numbers`() {
    val s = BackupJson.status("""{"tool_version":"1.1.0","root":"/r","writable":true,"storage_access":false,"backups":2,"compression":"gz","keep":5,"free_space":4096}""")
    assertTrue(s.writable); assertFalse(s.storageAccess); assertEquals(4096L, s.freeSpaceBytes)
}

@Test fun `restore parses a missing safety archive as null`() {
    val r = BackupJson.restored("""{"name":"n","root":"/r","safety":"","packages_installed":1,"packages_failed":0}""")
    assertNull(r.safety)
}

@Test fun `last json line skips prompts and human output`() {
    val transcript = "Restored n\r\n{\"deleted\":\"n\",\"root\":\"/r\"}\r\n"
    assertEquals("""{"deleted":"n","root":"/r"}""", BackupJson.lastJsonLine(transcript))
}

@Test(expected = BackupJsonException::class)
fun `malformed json raises`() { BackupJson.list("not json") }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.BackupSummaryTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement the parsers**

`lastJsonLine` scans lines from the end for the last trimmed line starting with `{` and ending with `}`; it strips `\r`. Kind comes from the JSON `kind` key, falling back to the `safety_pre_restore_` prefix. An empty `safety` string maps to `null`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.BackupSummaryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/backup/BackupSummary.kt \
        app/src/test/java/com/termux/app/domain/backup/BackupSummaryTest.kt
git commit -m "Added(app): Parse termux-backup JSON results"
```

---

### Task 6: Script provisioner

**Files:**
- Create: `app/src/main/java/com/termux/app/data/backup/BackupScriptProvisioner.kt`
- Test: `app/src/test/java/com/termux/app/data/backup/BackupScriptProvisionerTest.kt`

**Interfaces:**
- Produces: `interface BackupAssetSource { fun files(): List<String>; fun read(path: String): ByteArray }`, `class ApkAssetSource(context: Context) : BackupAssetSource` (enumerates `termux-backup` and `termux-backup/lib`, includes `VERSION`), `sealed interface ProvisionResult { data object UpToDate; data class Installed(val version: String); data class Failed(val message: String) }`, `class BackupScriptProvisioner(assets: BackupAssetSource, installDir: File, binLink: File)` with `fun bundledVersion(): String?`, `fun isCurrent(): Boolean`, `fun provision(force: Boolean = false): ProvisionResult`.

- [ ] **Step 1: Write the failing tests**

`root`, `install` and `link` are `TemporaryFolder` paths created in `@Before`.

```kotlin
private fun source(version: String = "1.1.0") = object : BackupAssetSource {
    override fun files() = listOf("VERSION", "termux-backup", "lib/common.sh")
    override fun read(path: String) = when (path) {
        "VERSION" -> version.toByteArray()
        "termux-backup" -> "#!/bin/bash\n".toByteArray()
        "lib/common.sh" -> "TOOL_VERSION=1.1.0\n".toByteArray()
        else -> error("unexpected $path")
    }
}

@Test fun `fresh install copies files and links`() {
    val p = BackupScriptProvisioner(source(), File(root, "lib/termux-backup"), File(root, "bin/termux-backup"))
    assertEquals(ProvisionResult.Installed("1.1.0"), p.provision())
    assertTrue(File(root, "lib/termux-backup/termux-backup").canExecute())
    assertEquals(File(root, "lib/termux-backup/termux-backup").absolutePath,
        Files.readSymbolicLink(File(root, "bin/termux-backup").toPath()).toString())
    assertEquals("1.1.0", File(root, "lib/termux-backup/.app-version").readText())
}

@Test fun `matching version is a no-op`() {
    val p = BackupScriptProvisioner(source(), install, link)
    p.provision()
    File(install, "termux-backup").writeText("local edit")
    assertEquals(ProvisionResult.UpToDate, p.provision())
    assertEquals("local edit", File(install, "termux-backup").readText())
}

@Test fun `mismatched version overwrites`() {
    val p = BackupScriptProvisioner(source("1.1.0"), install, link); p.provision()
    val next = BackupScriptProvisioner(source("1.2.0"), install, link)
    assertEquals(ProvisionResult.Installed("1.2.0"), next.provision())
    assertEquals("1.2.0", File(install, ".app-version").readText())
}

@Test fun `force overwrites a matching version`() {
    val p = BackupScriptProvisioner(source(), install, link); p.provision()
    File(install, "termux-backup").writeText("local edit")
    assertEquals(ProvisionResult.Installed("1.1.0"), p.provision(force = true))
}

@Test fun `read only prefix returns failure not an exception`() {
    install.mkdirs(); install.setWritable(false)
    val result = BackupScriptProvisioner(source(), install, link).provision()
    assertTrue(result is ProvisionResult.Failed)
    assertTrue((result as ProvisionResult.Failed).message.contains(install.path))
    install.setWritable(true)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.BackupScriptProvisionerTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement the provisioner**

Write every asset into `installDir/.tmp-<pid>`, `chmod 755` the dispatcher (`File.setExecutable(true, true)`), write `.app-version`, then replace `installDir` by rename; refresh the symlink with `Files.deleteIfExists` + `createSymbolicLink` only when `provision` is called (never silently outside it). Catch `IOException` and return `Failed("<path>: <message>")`. `isCurrent()` is true when `.app-version` equals `bundledVersion()` and the dispatcher exists and is executable.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.BackupScriptProvisionerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/data/backup/BackupScriptProvisioner.kt \
        app/src/test/java/com/termux/app/data/backup/BackupScriptProvisionerTest.kt
git commit -m "Added(app): Provision the backup script from APK assets"
```

---

### Task 7: Settings entry and activity shell

**Files:**
- Modify: `app/src/main/res/xml/termux_debugging_preferences.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.kt`
- Create: `app/src/main/java/com/termux/app/ui/backup/BackupActivity.kt`
- Create: `app/src/main/java/com/termux/app/ui/backup/BackupState.kt`
- Test: `app/src/test/java/com/termux/app/settings/SettingsPreferencesXmlTest.kt` (modify), `app/src/test/java/com/termux/app/settings/SettingsIndexBuilderTest.kt` (modify)
- Test: `app/src/test/java/com/termux/app/LayeringTest.kt` (modify)

**Interfaces:**
- Consumes: nothing.
- Produces: settings row key `backup_restore`; `class BackupActivity : ComponentActivity()` that sets `TermuxShellTheme { BackupScreen(state = ..., actions = ...) }` with a placeholder state; `BackupUiState` with `val loading: Boolean`, `val provision: ProvisionResult?`, `val status: BackupStatus?`, `val entries: List<BackupEntry>`, `val busy: Boolean`, `val message: String?`, `val run: BackupProgress?`.

- [ ] **Step 1: Write the failing tests**

In `SettingsPreferencesXmlTest`, update the advanced-screen key set to include `"backup_restore"`, and add:

```kotlin
@Test
fun `backup row opens the backup activity`() {
    val row = parsePreferenceScreen(R.xml.termux_debugging_preferences).single { it.key == "backup_restore" }
    assertEquals("Backup & restore", resolveString(row.title))
    assertNull(row.fragment)
}
```

In `SettingsIndexBuilderTest`, add a case asserting the indexed set for `R.xml.termux_debugging_preferences` contains a row whose `key == "backup_restore"` and title contains "Backup".

In `LayeringTest`, add `"com/termux/app/ui/backup/BackupActivity.kt"` to the `ui` rule's `allowedFiles` (the activity is a composition root that binds the service).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.*" --tests "com.termux.app.LayeringTest"`
Expected: FAIL, missing key/row.

- [ ] **Step 3: Implement the row and the shell**

Add to `termux_debugging_preferences.xml`, after the notifications category:

```xml
<PreferenceCategory app:key="backup" app:title="@string/termux_backup_preferences_title">
    <Preference
        app:key="backup_restore"
        app:title="@string/termux_backup_preferences_title"
        app:summary="@string/termux_backup_preferences_summary" />
</PreferenceCategory>
```

Wire `findPreference<Preference>("backup_restore")` in `DebuggingPreferencesFragment` to start `BackupActivity`. Add the three strings and the `<activity android:name=".app.ui.backup.BackupActivity" ...>` block copied from `AppearanceActivity` in the manifest (`exported=false`, `configChanges` unchanged, `Theme.TermuxApp.DayNight.NoActionBar`, `tools:targetApi="n"`).

`BackupActivity` renders a scaffold with a title and a loading indicator using `BackupState` only; no disk or service access yet.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.settings.*" --tests "com.termux.app.LayeringTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/xml/termux_debugging_preferences.xml app/src/main/res/values/strings.xml \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.kt \
        app/src/main/java/com/termux/app/ui/backup app/src/test/java/com/termux/app/settings \
        app/src/test/java/com/termux/app/LayeringTest.kt
git commit -m "Added(app): Add a Backup and restore settings screen"
```

---

### Task 8: Hidden session runner

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxService.kt`
- Create: `app/src/main/java/com/termux/app/data/backup/BackupSessionClient.kt`
- Create: `app/src/main/java/com/termux/app/data/backup/BackupRunner.kt`
- Create: `app/src/main/java/com/termux/app/data/backup/BackupDefaults.kt`
- Test: `app/src/test/java/com/termux/app/data/backup/BackupRunnerTest.kt`

**Interfaces:**
- Consumes: `BackupCommand`, `BackupProgressParser`, `BackupJson`.
- Produces: `object BackupDefaults` with `val progressFile: File`, `val lockFile: File`, `val transferStateFile: File`, `val scriptInstallDir: File`, `val scriptBinLink: File`, `val scriptPath: File`, `const val pollIntervalMs = 500L`; `fun createBackupSession(executionCommand: ExecutionCommand, client: TerminalSessionClient): TermuxSession?` on `TermuxService`; `class BackupSessionClient : TermuxTerminalSessionClientBase()` with `@Volatile var shellPid: Int` set from `setTerminalShellPid`, `val finished: CompletableDeferred<Unit>` completed from `onSessionFinished`, and `var transcript: String?`; `data class RunLock(val command: String, val pid: Int, val startedAt: String)`; `sealed interface StartResult { data object Started; data class Refused(val reason: String); data class Failed(val message: String) }`; `class BackupRunner(serviceProvider, configDir: File, scriptPath: File, pidAlive: (Int) -> Boolean)` with `val state: StateFlow<BackupProgress>`, `fun refresh(): BackupProgress`, `fun start(command: BackupCommand, service: TermuxService): StartResult`, `fun cancel()`, `fun awaitResult(timeoutMs: Long = 3_600_000): String?` (returns the session transcript, or null on timeout), and `fun sweepStaleLock()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `stale lock is cleared`() {
    val runner = BackupRunner({ null }, dir, pidAlive = { false })
    File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")
    runner.sweepStaleLock()
    assertFalse(File(dir, "run.lock").exists())
}

@Test fun `live lock refuses a second start`() {
    val runner = BackupRunner({ null }, dir, pidAlive = { true })
    File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")
    val result = runner.start(BackupCommand.ListBackups, service = mockService())
    assertEquals(StartResult.Refused("A backup is already running"), result)
}

@Test fun `corrupt lock is treated as stale`() {
    val runner = BackupRunner({ null }, dir, pidAlive = { true })
    File(dir, "run.lock").writeText("garbage")
    runner.sweepStaleLock()
    assertFalse(File(dir, "run.lock").exists())
}

@Test fun `refresh reads the progress file`() {
    File(dir, "progress.jsonl").writeText("""{"event":"run_start","command":"backup","ts":"t"}
        {"event":"run_end","exit_code":0}""")
    val runner = BackupRunner({ null }, dir, scriptPath, pidAlive = { false })
    assertEquals(BackupRunState.Finished("backup", 0, true), runner.refresh().state)
}

@Test fun `running progress with a dead pid is reported as interrupted`() {
    File(dir, "progress.jsonl").writeText("""{"event":"run_start","command":"backup","ts":"t"}
        {"event":"phase","name":"archiving_home"}""")
    File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")
    val runner = BackupRunner({ null }, dir, scriptPath, pidAlive = { false })
    assertEquals(BackupRunState.Interrupted, runner.refresh().state)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.BackupRunnerTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement service method and runner**

`TermuxService.createBackupSession` mirrors `createFloatSession`: builds nothing itself, calls the private `executeTermuxSession(executionCommand, client)` and returns it without adding it to `mShellManager.mTermuxSessions` or updating the notification.

`BackupRunner.start` builds an `ExecutionCommand(TermuxShellManager.getNextShellId(), "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/bash", command.argv(scriptPath, progressFile.path).drop(1).toTypedArray(), null, TERMUX_HOME_DIR_PATH, Runner.TERMINAL_SESSION.getName(), false)` with `shellName = "backup"`, calls the service method, waits up to 2 s for `client.shellPid != 0`, writes the lock, then launches a refresh coroutine every 500 ms that stops on `Finished`/`Interrupted`. `cancel()` deletes the lock and calls `terminalSession.finishIfRunning()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.BackupRunnerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxService.kt \
        app/src/main/java/com/termux/app/data/backup/BackupSessionClient.kt \
        app/src/main/java/com/termux/app/data/backup/BackupDefaults.kt \
        app/src/main/java/com/termux/app/data/backup/BackupRunner.kt \
        app/src/test/java/com/termux/app/data/backup/BackupRunnerTest.kt
git commit -m "Added(app): Run termux-backup in a hidden session"
```

---

### Task 9: Screen state, provisioning and the backup list

**Files:**
- Create: `app/src/main/java/com/termux/app/ui/backup/BackupViewModel.kt`
- Create: `app/src/main/java/com/termux/app/ui/backup/BackupScreen.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupActivity.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupState.kt`
- Test: `app/src/test/java/com/termux/app/ui/backup/BackupViewModelTest.kt`

**Interfaces:**
- Consumes: `BackupScriptProvisioner`, `BackupRunner`, `BackupJson`, `BackupCommand`.
- Produces: `class BackupViewModel(provisioner, runner, serviceProvider, dispatcher)` with `val state: StateFlow<BackupUiState>`, `fun open()`, `fun start(command: BackupCommand)`, `fun cancel()`, `fun refreshList()`, `fun reinstallScript()`; `@Composable fun BackupScreen(state: BackupUiState, actions: BackupActions)`; `data class BackupActions(val onCreate: (BackupCommand.Backup) -> Unit, val onVerify: (BackupEntry) -> Unit, val onDelete: (BackupEntry) -> Unit, val onPrune: (Int) -> Unit, val onRestore: (BackupEntry) -> Unit, val onExport: (BackupEntry) -> Unit, val onImport: () -> Unit, val onCancel: () -> Unit, val onRetry: () -> Unit, val onReinstallScript: () -> Unit, val onGrantStorage: () -> Unit, val onRestart: () -> Unit)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `open provisions then loads status and list`() = runTest {
    val provisioner = FakeProvisioner(ProvisionResult.Installed("1.1.0"))
    val vm = BackupViewModel(provisioner, FakeRunner(), { null }, StandardTestDispatcher(testScheduler))
    vm.open(); advanceUntilIdle()
    assertEquals("1.1.0", (vm.state.value.provision as ProvisionResult.Installed).version)
    assertEquals(2, vm.state.value.entries.size)
}

@Test fun `provision failure disables actions and keeps the screen`() = runTest {
    val provisioner = FakeProvisioner(ProvisionResult.Failed("/p/lib: read-only"))
    val vm = BackupViewModel(provisioner, FakeRunner(), { null }, StandardTestDispatcher(testScheduler))
    vm.open(); advanceUntilIdle()
    assertTrue(vm.state.value.busy)
    assertEquals("/p/lib: read-only", vm.state.value.message)
}

@Test fun `a run refreshes the list when it finishes`() = runTest { /* FakeRunner emits Finished; assert refreshList called */ }

@Test fun `restore exit 3 is reported as partial success`() = runTest {
    val vm = BackupViewModel(..., FakeRunner(result = """{"name":"n","root":"/r","safety":"s","packages_installed":1,"packages_failed":2}""", exit = 3), ...)
    vm.start(BackupCommand.Restore("n", safety = true, packages = true)); vm.onRunFinished(3)
    assertTrue(vm.state.value.partialSuccess)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.backup.BackupViewModelTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement the state, view model and screen**

`open()` calls `provision()` off the main thread; on success runs `Status` and `ListBackups` through the runner, parses with `BackupJson.lastJsonLine(transcript)`. The screen renders the header card (root, writable, storage access, free space, count, shared-storage warning), the create form (label, compression default from `status.compression`, keep default `status.keep`, three switches: include `$HOME` on, include `$PREFIX/etc` on, exclude secrets off), and the list (name, kind badge, created, size, packages) with Verify/Delete per entry. Actions are disabled when `busy` or provisioning failed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.backup.BackupViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/backup app/src/test/java/com/termux/app/ui/backup
git commit -m "Added(app): Show backup status, creation and the backup list"
```

---

### Task 10: Restore, verify, delete and prune actions

**Files:**
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupViewModel.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupScreen.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupState.kt`
- Test: `app/src/test/java/com/termux/app/ui/backup/BackupViewModelTest.kt`

**Interfaces:**
- Consumes: Task 9 state and actions.
- Produces: `BackupUiState.restoreDialog: BackupEntry?`, `BackupUiState.confirmDelete: BackupEntry?`, `BackupUiState.result: RunResult?` where `sealed interface RunResult { data class Success(val summary: String); data class Partial(val summary: String); data class Failure(val exitCode: Int, val lastError: String); data object Cancelled }`; actions `onConfirmRestore(entry, safety: Boolean, packages: Boolean)`, `onConfirmDelete(entry)`, `onDismissDialog()`, and `fun onRunFinished(exitCode: Int)`, which parses the session transcript into a `RunResult` and refreshes the list.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `restore confirmation maps toggles`() = runTest {
    val runner = RecordingRunner()
    val vm = vmWith(runner)
    vm.onConfirmRestore(entry, safety = false, packages = true)
    assertEquals(BackupCommand.Restore("n", safety = false, packages = true), runner.lastCommand)
}

@Test fun `verify success is shown inline`() = runTest { /* VerifiedBackup(valid = true) -> state.verified["n"] == true */ }

@Test fun `delete requires confirmation before the command`() = runTest {
    vm.onDelete(entry)
    assertEquals(null, runner.lastCommand)
    assertNotNull(vm.state.value.confirmDelete)
    vm.onConfirmDelete(entry)
    assertEquals(BackupCommand.Delete("n"), runner.lastCommand)
}

@Test fun `prune refuses zero when safety entries exist`() = runTest { /* keep 0 allowed but safety untouched is asserted by the script; UI only sends --keep */ }

@Test fun `failure carries the last stderr line`() = runTest {
    val vm = vmWith(RecordingRunner(transcript = "termux-backup: backup failed; no backup was kept\n", exit = 2))
    vm.start(BackupCommand.Backup(null, "gz", null, false, true, true)); vm.onRunFinished(2)
    assertEquals("termux-backup: backup failed; no backup was kept", (vm.state.value.result as RunResult.Failure).lastError)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.backup.BackupViewModelTest"`
Expected: FAIL on the new cases.

- [ ] **Step 3: Implement dialogs, result card and refresh**

Restore dialog names backup, created time, size and both target paths; toggles default on. On success, send `TERMUX_ACTIVITY.ACTION_RELOAD_STYLE` with `EXTRA_RECREATE_ACTIVITY=false` from the activity (not the ViewModel, which may not import it) and show "Restart app". After every mutating command (backup, restore, delete, prune) refresh the list and status.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.ui.backup.BackupViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/ui/backup app/src/test/java/com/termux/app/ui/backup
git commit -m "Added(app): Restore, verify, delete and prune from the backup screen"
```

---

### Task 11: SAF names and sums validation

**Files:**
- Create: `app/src/main/java/com/termux/app/domain/backup/SafBackupName.kt`
- Create: `app/src/main/java/com/termux/app/domain/backup/SafBackupManifest.kt`
- Test: `app/src/test/java/com/termux/app/domain/backup/SafBackupNameTest.kt`
- Test: `app/src/test/java/com/termux/app/domain/backup/SafBackupManifestTest.kt`

**Interfaces:**
- Produces: `object SafBackupName { fun sanitize(displayName: String): String; fun destination(displayName: String, manifestCreatedAt: String?, now: Instant): String; fun collision(base: String, taken: Set<String>): String }`; `data class SumEntry(val name: String, val sha256: String)`; `class InvalidBackupException(message: String) : Exception(message)`; `object SafBackupManifest { fun parseSums(text: String): List<SumEntry>; fun formatVersion(json: String?): Int?; fun createdAt(json: String?): String? }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `sanitize keeps safe names and replaces the rest`() {
    assertEquals("termux_backup_20260101_000000", SafBackupName.sanitize("termux_backup_20260101_000000"))
    assertEquals("my-backup", SafBackupName.sanitize("my backup"))
    assertEquals("backup-2", SafBackupName.sanitize("../backup (2)"))
}

@Test fun `destination derives a listable name`() {
    val now = Instant.parse("2026-11-17T10:00:00Z")
    assertEquals("termux_backup_20260101_101530", SafBackupName.destination("Backups", "2026-01-01T10:15:30Z", now))
    assertEquals("termux_backup_20261117_100000", SafBackupName.destination("Backups", null, now))
    assertEquals("safety_pre_restore_20260101_000000", SafBackupName.destination("safety_pre_restore_20260101_000000", null, now))
}

@Test fun `collision appends a numeric suffix`() {
    assertEquals("termux_backup_x-2", SafBackupName.collision("termux_backup_x", setOf("termux_backup_x")))
    assertEquals("termux_backup_x-3", SafBackupName.collision("termux_backup_x", setOf("termux_backup_x", "termux_backup_x-2")))
}

@Test fun `sums parse and reject traversal`() {
    val line = "a".repeat(64) + "  home.tar.gz"
    assertEquals(listOf(SumEntry("home.tar.gz", "a".repeat(64))), SafBackupManifest.parseSums("$line\n"))
    assertThrows(InvalidBackupException::class.java) { SafBackupManifest.parseSums("${"a".repeat(64)}  ../evil.tar.gz") }
    assertThrows(InvalidBackupException::class.java) { SafBackupManifest.parseSums("${"a".repeat(64)}  a/b.tar.gz") }
    assertThrows(InvalidBackupException::class.java) {
        SafBackupManifest.parseSums("${"a".repeat(64)}  x.tar.gz\n${"a".repeat(64)}  x.tar.gz")
    }
}

@Test fun `manifest version guard reads only known keys`() {
    assertEquals(1, SafBackupManifest.formatVersion("""{"format_version":1,"created_at":"2026-01-01T10:15:30Z"}"""))
    assertNull(SafBackupManifest.formatVersion(null))
    assertEquals("2026-01-01T10:15:30Z", SafBackupManifest.createdAt("""{"created_at":"2026-01-01T10:15:30Z"}"""))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.SafBackup*"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement sanitising, naming and validation**

`sanitize` maps every character outside `[A-Za-z0-9._-]` to `-`, collapses repeated `-`, trims leading/trailing dots and dashes, and returns `backup` when empty. `destination` returns the sanitised display name when it starts with `termux_backup_` or `safety_pre_restore_`, else tries manifest `created_at` parsed as ISO-8601 to `yyyyMMdd_HHmmss`, else `now` in UTC. `parseSums` accepts exactly `<64 hex><two spaces><name>`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.domain.backup.SafBackup*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/domain/backup/SafBackupName.kt \
        app/src/main/java/com/termux/app/domain/backup/SafBackupManifest.kt \
        app/src/test/java/com/termux/app/domain/backup/SafBackupNameTest.kt \
        app/src/test/java/com/termux/app/domain/backup/SafBackupManifestTest.kt
git commit -m "Added(app): Validate SAF backup names and checksums"
```

---

### Task 12: SAF tree and transfer

**Files:**
- Create: `app/src/main/java/com/termux/app/data/backup/SafDocumentTree.kt`
- Create: `app/src/main/java/com/termux/app/data/backup/SafBackupTransfer.kt`
- Test: `app/src/test/java/com/termux/app/data/backup/SafBackupTransferTest.kt`

**Interfaces:**
- Produces: `interface SafDocumentTree { data class Document(val id: String, val name: String, val size: Long?); fun displayName(): String; fun children(): List<Document>; fun createDirectory(name: String): Document; fun createFile(parent: Document, name: String): Document; fun openInput(document: Document): InputStream; fun openOutput(document: Document): OutputStream; fun delete(document: Document): Boolean }`; `class AndroidSafDocumentTree(context: Context, treeUri: Uri) : SafDocumentTree`; `data class CopyProgress(val fileName: String, val bytesDone: Long, val bytesTotal: Long, val indeterminate: Boolean)`; `sealed interface TransferResult { data class Success(val name: String); data object Cancelled; data class Failed(val message: String) }`; `class SafBackupTransfer(private val progress: (CopyProgress) -> Unit, private val cancellation: () -> Boolean)` with `fun export(sourceDir: File, tree: SafDocumentTree): TransferResult` and `fun importInto(tree: SafDocumentTree, destinationRoot: File, takenNames: Set<String>): TransferResult`.

- [ ] **Step 1: Write the failing tests**

`FakeTree` (in-memory `SafDocumentTree`), `backupDirWith`, `backupDirNamed` and `sha` are fixtures in
this test file; `FakeTree.displayName()` returns the directory name the case is about.

```kotlin
@Test fun `export copies the summed set and verifies each hash`() {
    val tree = FakeTree(); val dir = backupDirWith(sums = mapOf("home.tar.gz" to sha("abc")))
    val result = SafBackupTransfer({}, { false }).export(dir, tree)
    assertEquals(TransferResult.Success(dir.name), result)
    assertEquals(setOf("SHA256SUMS","manifest.json","packages.txt","home.tar.gz"), tree.namesIn(dir.name))
}

@Test fun `export hash mismatch deletes the created directory`() {
    val tree = FakeTree(); val dir = backupDirWith(sums = mapOf("home.tar.gz" to "0".repeat(64)))
    assertTrue(SafBackupTransfer({}, { false }).export(dir, tree) is TransferResult.Failed)
    assertTrue(tree.children().none { it.name == dir.name })
}

@Test fun `existing destination refuses without touching it`() {
    val tree = FakeTree(existing = setOf("termux_backup_x"))
    assertTrue(SafBackupTransfer({}, { false }).export(backupDirNamed("termux_backup_x"), tree) is TransferResult.Failed)
}

@Test fun `import verifies and is not listed on mismatch`() {
    val tree = sourceTree(payload = "tampered", sumsFor = "abc")
    val result = SafBackupTransfer({}, { false }).importInto(tree, root, emptySet())
    assertTrue(result is TransferResult.Failed)
    assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("termux_backup_") })
}

@Test fun `import succeeds and picks a collision suffix`() {
    val result = SafBackupTransfer({}, { false }).importInto(sourceTree(payload = "abc", sumsFor = "abc"), root, setOf("termux_backup_x"))
    assertEquals(TransferResult.Success("termux_backup_x-2"), result)
    assertEquals("abc", File(root, "termux_backup_x-2/home.tar.gz").readText())
}

@Test fun `unknown provider size keeps the bar indeterminate`() {
    val seen = mutableListOf<CopyProgress>()
    SafBackupTransfer({ seen += it }, { false }).export(backupDirWith(sums = mapOf("home.tar.gz" to sha("abc")), nullSize = true), FakeTree())
    assertTrue(seen.any { it.indeterminate })
}

@Test fun `cancellation deletes partial output`() {
    val tree = FakeTree()
    val result = SafBackupTransfer({}, { true }).export(backupDirWith(sums = mapOf("home.tar.gz" to sha("abc"))), tree)
    assertEquals(TransferResult.Cancelled, result)
    assertTrue(tree.children().isEmpty())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.SafBackupTransferTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement tree access and transfer**

`AndroidSafDocumentTree` queries `DocumentsContract.buildChildDocumentsUriUsingTree` for `COLUMN_DOCUMENT_ID`/`COLUMN_DISPLAY_NAME`/`COLUMN_SIZE`/`COLUMN_MIME_TYPE`, creates with `DocumentsContract.createDocument`, and streams with `openInputStream`/`openOutputStream`. The transfer reads `SHA256SUMS`, copies with an 8 KiB buffer while updating a `MessageDigest`, compares hex, deletes its own output on any failure or cancellation, and calls `progress` after every buffer. Import writes into `<root>/.tmp-import-<pid>`, verifies `format_version == 1` when readable, and renames with `File.renameTo` after verification.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.SafBackupTransferTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/data/backup/SafDocumentTree.kt \
        app/src/main/java/com/termux/app/data/backup/SafBackupTransfer.kt \
        app/src/test/java/com/termux/app/data/backup/SafBackupTransferTest.kt
git commit -m "Added(app): Export and import backup directories through SAF"
```

---

### Task 13: Transfer coordinator and picker wiring

**Files:**
- Create: `app/src/main/java/com/termux/app/data/backup/BackupTransferCoordinator.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupActivity.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupViewModel.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupScreen.kt`
- Modify: `app/src/main/java/com/termux/app/ui/backup/BackupState.kt`
- Test: `app/src/test/java/com/termux/app/data/backup/BackupTransferCoordinatorTest.kt`

**Interfaces:**
- Consumes: `SafBackupTransfer`, `BackupRunner`, `BackupScriptProvisioner`.
- Produces: `data class TransferState(val running: Boolean, val operation: String?, val progress: CopyProgress?, val result: TransferResult?)`; `class BackupTransferCoordinator private constructor(context: Context)` with `val state: StateFlow<TransferState>`, `fun startExport(sourceDir: File, tree: SafDocumentTree)`, `fun startImport(tree: SafDocumentTree, root: File)`, `fun cancel()`, `fun sweepStaleImports(root: File)`, `companion object { fun get(context: Context): BackupTransferCoordinator; fun forTest(transfer: FakeTransfer, busy: () -> Boolean): BackupTransferCoordinator }`; and `BackupUiState.transfer: TransferState?`, added in this task.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `one transfer at a time`() = runTest {
    val coordinator = BackupTransferCoordinator.forTest(FakeTransfer(delayMs = 100), busy = { false })
    coordinator.startImport(tree, root)
    coordinator.startExport(root, tree)
    assertTrue(coordinator.state.value.running)
    assertEquals("import", coordinator.state.value.operation)
}

@Test fun `refuses while a script run holds the lock`() = runTest {
    val coordinator = BackupTransferCoordinator.forTest(FakeTransfer(), busy = { true })
    coordinator.startImport(tree, root)
    assertNull(coordinator.state.value.operation)
    assertTrue(coordinator.state.value.result is TransferResult.Failed)
}

@Test fun `stale import temp directories are swept`() {
    File(root, ".tmp-import-123").mkdirs()
    BackupTransferCoordinator.forTest(FakeTransfer(), busy = { false }).sweepStaleImports(root)
    assertFalse(File(root, ".tmp-import-123").exists())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.BackupTransferCoordinatorTest"`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implement the coordinator and pickers**

Register two `ActivityResultContracts.OpenDocumentTree` launchers in `BackupActivity`: one for export (captures the entry being exported) and one for import. On result, wrap the URI in `AndroidSafDocumentTree`, call the coordinator, and expose its `state` to the ViewModel. The coordinator owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, writes `transfer.json` before creating anything in a foreign tree, and blocks start when `run.lock` exists. The screen shows per-entry "Export…", header "Import backup…", a byte-based progress bar with the current file, and Cancel.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.termux.app.data.backup.BackupTransferCoordinatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/data/backup/BackupTransferCoordinator.kt \
        app/src/main/java/com/termux/app/ui/backup \
        app/src/test/java/com/termux/app/data/backup/BackupTransferCoordinatorTest.kt
git commit -m "Added(app): Export and import backups with the system picker"
```

---

### Task 14: Documentation, ledger and full verification

**Files:**
- Modify: `scripts/termux-backup/README.md`
- Modify: `README.md`
- Modify: `docs/ledger/outstanding-work.md`
- Modify: `docs/ledger/manual-checks.md`

**Interfaces:**
- Consumes: everything above.
- Produces: user-facing documentation and the device-check section that gates the feature.

- [ ] **Step 1: Update documentation**

`scripts/termux-backup/README.md`: add `--progress-file` under automation, stating it writes JSONL events for the app and that human output is unchanged. Root `README.md`: extend the backup section to point at Settings → Advanced → Backup & restore, including SAF export/import. `docs/ledger/outstanding-work.md` §5.5: mark phase 2 shipped with its commits, keep scheduling deferred, and note SAF landed. `docs/ledger/manual-checks.md`: add D-35 to D-42 in the style of the existing sections, covering: fresh-install provisioning and settings-search row; backup attach after closing the screen; cancel; corrupted verify; restore dialog + safety + restart prompt; revoked storage permission; SAF export to Downloads and import back with a corrupted-copy rejection; cancelled export cleanup.

- [ ] **Step 2: Run the full verification**

Run: `./gradlew test`
Expected: green, including `LayeringTest` and all new suites.

Run: `bash scripts/termux-backup/tests/run.sh && shellcheck -S warning scripts/termux-backup/termux-backup scripts/termux-backup/lib/*.sh scripts/termux-backup/tests/*.sh`
Expected: all shell cases PASS, shellcheck exit 0.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (NDK build may need the workspace's `-PndkVersion` workaround; the Java/Kotlin side must compile either way).

Run: `unzip -l app/build/outputs/apk/debug/*.apk | grep 'assets/termux-backup'`
Expected: `termux-backup`, `VERSION` and `lib/common.sh` entries.

- [ ] **Step 3: Commit**

```bash
git add scripts/termux-backup/README.md README.md docs/ledger
git commit -m "Added(docs): Document the in-app backup and restore screen"
```
