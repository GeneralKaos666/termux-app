package com.termux.app.data.backup

import com.termux.app.domain.backup.InvalidBackupException
import com.termux.app.domain.backup.SafBackupManifest
import com.termux.app.domain.backup.SafBackupName
import com.termux.app.domain.backup.SumEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Progress of one file being copied, in bytes; [indeterminate] when the provider reports no size. */
data class CopyProgress(
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val indeterminate: Boolean,
)

/** Outcome of an export or an import. */
sealed interface TransferResult {

    /** Copied successfully; [name] is the backup directory name. */
    data class Success(val name: String) : TransferResult

    /** The user cancelled; partial output was removed. */
    data object Cancelled : TransferResult

    /** The transfer failed; partial output was removed. */
    data class Failed(val message: String) : TransferResult
}

/**
 * Copies a backup directory to and from a SAF tree, verifying `SHA256SUMS` on both directions.
 *
 * The checksum set, not the directory listing, decides which files must exist and which are
 * verified, so an incomplete backup is refused instead of silently exported. Destination names are
 * always the validated names from the sums file, never a provider-supplied display name.
 * Destinations that already exist are never overwritten, and any failure deletes what this class
 * created, so a half-written backup never becomes listable.
 *
 * @param freeSpace free bytes at the destination root; injectable for tests
 */
class SafBackupTransfer(
    private val progress: (CopyProgress) -> Unit,
    private val cancellation: () -> Boolean,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : BackupTransfer {

    /** Copies [sourceDir]'s summed files into a new directory in [tree]. */
    override fun export(sourceDir: File, tree: SafDocumentTree): TransferResult {
        val sums = try {
            SafBackupManifest.parseSums(File(sourceDir, SUMS_FILE).readText())
        } catch (e: Exception) {
            return TransferResult.Failed("${sourceDir.path}: $SUMS_FILE is missing or invalid")
        }

        val localFiles = sourceDir.listFiles()?.filter { it.isFile }.orEmpty()
        val byName = localFiles.associateBy { it.name }
        for (entry in sums) {
            if (entry.name !in byName) return TransferResult.Failed("missing file: ${entry.name}")
        }

        val name = SafBackupName.sanitize(sourceDir.name)
        if (tree.children(null).any { it.name == name }) {
            return TransferResult.Failed("$name already exists in ${tree.displayName()}")
        }

        val directory = try {
            tree.createDirectory(name)
        } catch (e: Exception) {
            return TransferResult.Failed("could not create $name: ${e.message}")
        }

        val toCopy = orderedCopySet(sums, byName, localFiles, METADATA_FILES + SUMS_FILE) { it.name }
        try {
            for ((entry, file) in toCopy) {
                if (cancellation()) return abort(tree, directory)
                val document = tree.createFile(directory, file.name)
                val digest = file.inputStream().use { input ->
                    tree.openOutput(document).use { output ->
                        copy(input, output, file.name, document.size)
                    }
                } ?: return abort(tree, directory)
                if (entry != null && digest != entry.sha256) {
                    return abort(tree, directory, "checksum mismatch: ${file.name}")
                }
            }
        } catch (e: Exception) {
            return abort(tree, directory, "export failed: ${e.message}")
        }

        val assigned = tree.children(null).firstOrNull { it.id == directory.id }?.name ?: directory.name
        return TransferResult.Success(assigned)
    }

    /**
     * Copies the backup directory in [tree] into [destinationRoot], verifying its checksums and
     * renaming it to a name the CLI lists.
     */
    override fun importInto(
        tree: SafDocumentTree,
        destinationRoot: File,
        takenNames: Set<String>,
    ): TransferResult {
        val rootChildren = tree.children(null)
        for (child in rootChildren) {
            if (!isSafeName(child.name)) return TransferResult.Failed("illegal file name: ${child.name}")
        }

        val byName = rootChildren.associateBy { it.name }
        val sumsDocument = byName[SUMS_FILE]
            ?: return TransferResult.Failed("$SUMS_FILE is missing in ${tree.displayName()}")
        val sums = try {
            SafBackupManifest.parseSums(readText(tree, sumsDocument))
        } catch (e: InvalidBackupException) {
            return TransferResult.Failed(e.message ?: "invalid checksums")
        }
        for (entry in sums) {
            if (entry.name !in byName) return TransferResult.Failed("missing file: ${entry.name}")
        }

        val manifest = byName[MANIFEST_FILE]?.let { readText(tree, it) }
        val version = SafBackupManifest.formatVersion(manifest)
        if (version != null && version != FORMAT_VERSION) {
            return TransferResult.Failed("unsupported backup format: $version")
        }

        val needed = sums.sumOf { byName.getValue(it.name).size ?: 0L }
        val required = needed + needed / 20 + FREE_SPACE_MARGIN
        val available = freeSpace(destinationRoot)
        if (available in 0 until required) {
            return TransferResult.Failed(
                "not enough free space in ${destinationRoot.path}: $available bytes, $required needed",
            )
        }

        val name = SafBackupName.collision(
            SafBackupName.destination(
                tree.displayName(),
                SafBackupManifest.createdAt(manifest),
                Instant.now(),
            ),
            takenNames + rootChildren.map { it.name }.toSet(),
        )

        val staging = File(destinationRoot, "$TEMP_PREFIX${UUID.randomUUID()}")
        if (!staging.mkdirs()) return TransferResult.Failed("could not create ${staging.path}")

        val toCopy = orderedCopySet(sums, byName, rootChildren, METADATA_FILES) { it.name }
        try {
            for ((entry, document) in toCopy) {
                if (cancellation()) return abortDirectory(staging)
                val digest = tree.openInput(document).use { input ->
                    File(staging, document.name).outputStream().use { output ->
                        copy(input, output, document.name, document.size)
                    }
                } ?: return abortDirectory(staging)
                if (entry != null && digest != entry.sha256) {
                    return abortDirectory(staging, "checksum mismatch: ${document.name}")
                }
            }
            // SHA256SUMS itself is part of the directory the CLI verifies later.
            File(staging, SUMS_FILE).writeText(readText(tree, sumsDocument))
        } catch (e: Exception) {
            return abortDirectory(staging, "import failed: ${e.message}")
        }

        val destination = File(destinationRoot, name)
        if (!staging.renameTo(destination)) {
            return abortDirectory(staging, "could not move the import into ${destination.path}")
        }
        return TransferResult.Success(name)
    }

    /**
     * The summed files in sums order, followed by the metadata files the script also writes. An
     * entry is null for metadata that is not covered by `SHA256SUMS`.
     */
    private fun <T> orderedCopySet(
        sums: List<SumEntry>,
        byName: Map<String, T>,
        all: List<T>,
        extras: Set<String>,
        nameOf: (T) -> String,
    ): List<Pair<SumEntry?, T>> {
        val copied = sums.map { it to byName.getValue(it.name) }
        val metadata = all.filter { item ->
            val name = nameOf(item)
            name in extras && sums.none { it.name == name }
        }.map { null to it }
        return copied + metadata
    }

    private fun readText(tree: SafDocumentTree, document: SafDocumentTree.Document): String {
        return tree.openInput(document).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Copies [input] to [output] and returns the SHA-256 of the bytes written, or null when the
     * user cancels mid-file.
     */
    private fun copy(input: InputStream, output: OutputStream, name: String, size: Long?): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        val total = size ?: 0L
        val indeterminate = size == null
        progress(CopyProgress(name, done, total, indeterminate))
        while (true) {
            if (cancellation()) return null
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            done += read
            progress(CopyProgress(name, done, total, indeterminate))
        }
        output.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun abort(
        tree: SafDocumentTree,
        directory: SafDocumentTree.Document,
        message: String? = null,
    ): TransferResult {
        runCatching { tree.delete(directory) }
        return if (message == null) TransferResult.Cancelled else TransferResult.Failed(message)
    }

    private fun abortDirectory(staging: File, message: String? = null): TransferResult {
        staging.deleteRecursively()
        return if (message == null) TransferResult.Cancelled else TransferResult.Failed(message)
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private companion object {
        const val SUMS_FILE = "SHA256SUMS"
        const val MANIFEST_FILE = "manifest.json"
        const val PACKAGES_FILE = "packages.txt"
        val METADATA_FILES = setOf(MANIFEST_FILE, PACKAGES_FILE)
        const val FORMAT_VERSION = 1
        const val TEMP_PREFIX = ".tmp-import-"
        const val BUFFER_SIZE = 8 * 1024
        const val FREE_SPACE_MARGIN = 16L * 1024 * 1024
    }
}
