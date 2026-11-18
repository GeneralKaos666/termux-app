package com.termux.app.domain.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Whether a directory is a user backup or the safety archive taken before a restore. */
enum class BackupKind {
    BACKUP,
    SAFETY,
}

/** One backup directory as `termux-backup list --json` reports it. */
data class BackupEntry(
    val name: String,
    val kind: BackupKind,
    val createdAt: String,
    val sizeBytes: Long,
    val packages: Int,
)

/** The `list` result: the backup root and every backup directory in it. */
data class BackupList(val root: String, val entries: List<BackupEntry>)

/** The `status` result: where backups live and how they are configured. */
data class BackupStatus(
    val root: String,
    val writable: Boolean,
    val storageAccess: Boolean,
    val backups: Int,
    val compression: String,
    val keep: Int,
    val freeSpaceBytes: Long,
)

/** The `backup` result: the directory that was created. */
data class CreatedBackup(
    val name: String,
    val root: String,
    val sizeBytes: Long,
    val packages: Int,
)

/**
 * The `restore` result. [safety] is the safety archive made before the restore, or `null` when
 * none was made.
 */
data class RestoredBackup(
    val name: String,
    val root: String,
    val safety: String?,
    val packagesInstalled: Int,
    val packagesFailed: Int,
)

/** The `verify` result: whether every recorded checksum matched. */
data class VerifiedBackup(val name: String, val valid: Boolean)

/** The `delete` result: the name that was removed and the root it was in. */
data class DeletedBackup(val name: String, val root: String)

/** The `prune` result: how many archives were removed, how many remain, and the limit used. */
data class PrunedBackups(val removed: Int, val kept: Int, val keep: Int)

/** A result object that cannot be read. The message is meant to be shown to the user. */
class BackupJsonException(message: String) : Exception(message)

/**
 * Parses the one JSON object each `termux-backup` command prints with `--json`.
 *
 * The keys and their types are those written by `scripts/termux-backup/lib`: `report.sh` for list
 * and status, `archive.sh` for backup, `restore.sh` for restore, and `storage.sh` for verify,
 * delete and prune. Unknown keys are ignored, and a missing optional key keeps a safe default.
 */
object BackupJson {

    /** The prefix `dir_name_safety` in `storage.sh` gives every safety archive. */
    private const val SAFETY_PREFIX = "safety_pre_restore_"

    /** Parse a `list` result. */
    fun list(raw: String): BackupList {
        val json = parse(raw, "list")
        val items = json.optJSONArray("backups") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val name = item.optString("name")
                add(
                    BackupEntry(
                        name = name,
                        kind = kindOf(item.optString("kind"), name),
                        createdAt = item.optString("created_at"),
                        sizeBytes = item.optLong("size", 0L),
                        packages = item.optInt("packages", 0),
                    ),
                )
            }
        }
        return BackupList(root = json.optString("root"), entries = entries)
    }

    /** Parse a `status` result. */
    fun status(raw: String): BackupStatus {
        val json = parse(raw, "status")
        return BackupStatus(
            root = json.optString("root"),
            writable = json.optBoolean("writable", false),
            storageAccess = json.optBoolean("storage_access", false),
            backups = json.optInt("backups", 0),
            compression = json.optString("compression"),
            keep = json.optInt("keep", 0),
            freeSpaceBytes = json.optLong("free_space", 0L),
        )
    }

    /** Parse a `backup` result. */
    fun created(raw: String): CreatedBackup {
        val json = parse(raw, "backup")
        return CreatedBackup(
            name = json.optString("name"),
            root = json.optString("root"),
            sizeBytes = json.optLong("size", 0L),
            packages = json.optInt("packages", 0),
        )
    }

    /** Parse a `restore` result. An empty `safety` string means no safety archive was made. */
    fun restored(raw: String): RestoredBackup {
        val json = parse(raw, "restore")
        return RestoredBackup(
            name = json.optString("name"),
            root = json.optString("root"),
            safety = json.optString("safety").ifEmpty { null },
            packagesInstalled = json.optInt("packages_installed", 0),
            packagesFailed = json.optInt("packages_failed", 0),
        )
    }

    /** Parse a `verify` result. */
    fun verified(raw: String): VerifiedBackup {
        val json = parse(raw, "verify")
        return VerifiedBackup(
            name = json.optString("name"),
            valid = json.optBoolean("valid", false),
        )
    }

    /** Parse a `delete` result. The CLI names the removed directory in the `deleted` key. */
    fun deleted(raw: String): DeletedBackup {
        val json = parse(raw, "delete")
        return DeletedBackup(
            name = json.optString("deleted"),
            root = json.optString("root"),
        )
    }

    /** Parse a `prune` result. */
    fun pruned(raw: String): PrunedBackups {
        val json = parse(raw, "prune")
        return PrunedBackups(
            removed = json.optInt("removed", 0),
            kept = json.optInt("kept", 0),
            keep = json.optInt("keep", 0),
        )
    }

    /**
     * The last line of a terminal [transcript] that is a whole JSON object.
     *
     * A run prints human output and prompts around its one JSON object, and the transcript uses
     * CRLF, so every line is trimmed and the scan runs from the end. A transcript without such a
     * line raises [BackupJsonException] rather than returning text that cannot be parsed.
     */
    fun lastJsonLine(transcript: String): String {
        for (line in transcript.split('\n').asReversed()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        }
        throw BackupJsonException("no JSON object found in the terminal transcript")
    }

    /** Parse [raw] as one object, turning a syntax error into a readable [BackupJsonException]. */
    private fun parse(raw: String, command: String): JSONObject = try {
        JSONObject(raw.trim())
    } catch (error: JSONException) {
        throw BackupJsonException("cannot parse the $command result: ${error.message}")
    }

    /** The `kind` key of one entry, falling back to the directory name prefix. */
    private fun kindOf(wire: String, name: String): BackupKind = when (wire) {
        "safety" -> BackupKind.SAFETY
        "backup" -> BackupKind.BACKUP
        else -> if (name.startsWith(SAFETY_PREFIX)) BackupKind.SAFETY else BackupKind.BACKUP
    }
}
