package com.termux.app.data.backup

import com.termux.app.domain.backup.InvalidBackupException
import com.termux.app.domain.backup.SafBackupManifest
import com.termux.app.domain.backup.SafBackupName
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

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
 * Destinations that already exist are never overwritten, and any failure deletes what this class
 * created, so a half-written backup never becomes listable.
 */
class SafBackupTransfer(
    private val progress: (CopyProgress) -> Unit,
    private val cancellation: () -> Boolean,
) {

    /** Copies [sourceDir]'s files into a new directory in [tree]. */
    fun export(sourceDir: File, tree: SafDocumentTree): TransferResult {
        val sums = readLocalSums(sourceDir) ?: return TransferResult.Failed(
            "${sourceDir.path}: SHA256SUMS is missing or invalid",
        )
        val name = SafBackupName.sanitize(sourceDir.name)
        if (tree.children(null).any { it.name == name }) {
            return TransferResult.Failed("$name already exists in ${tree.displayName()}")
        }

        val directory = try {
            tree.createDirectory(name)
        } catch (e: Exception) {
            return TransferResult.Failed("could not create $name: ${e.message}")
        }

        val expected = sums.associate { it.name to it.sha256 }
        val files = sourceDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            if (cancellation()) return abort(tree, directory)
            val digest = try {
                val document = tree.createFile(directory, file.name)
                file.inputStream().use { input ->
                    tree.openOutput(document).use { output ->
                        copy(input, output, file.name, document.size)
                    }
                }
            } catch (e: IOException) {
                return abort(tree, directory, e.message)
            }
            val wanted = expected[file.name] ?: continue
            if (digest != wanted) {
                return abort(tree, directory, "checksum mismatch: ${file.name}")
            }
        }
        return TransferResult.Success(name)
    }

    /**
     * Copies the backup directory in [tree] into [destinationRoot], verifying its checksums and
     * renaming it to a name the CLI lists.
     */
    fun importInto(
        tree: SafDocumentTree,
        destinationRoot: File,
        takenNames: Set<String>,
    ): TransferResult {
        val rootChildren = tree.children(null)
        val sumsDocument = rootChildren.firstOrNull { it.name == SUMS_FILE }
            ?: return TransferResult.Failed("$SUMS_FILE is missing in ${tree.displayName()}")
        val sums = try {
            SafBackupManifest.parseSums(readText(tree, sumsDocument))
        } catch (e: InvalidBackupException) {
            return TransferResult.Failed(e.message ?: "invalid checksums")
        }

        val manifest = rootChildren.firstOrNull { it.name == MANIFEST_FILE }
            ?.let { readText(tree, it) }
        val version = SafBackupManifest.formatVersion(manifest)
        if (version != null && version != FORMAT_VERSION) {
            return TransferResult.Failed("unsupported backup format: $version")
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

        val expected = sums.associate { it.name to it.sha256 }
        for (child in rootChildren.filter { it.name != SUMS_FILE }) {
            if (cancellation()) return abortDirectory(staging)
            val digest = try {
                tree.openInput(child).use { input ->
                    File(staging, child.name).outputStream().use { output ->
                        copy(input, output, child.name, child.size)
                    }
                }
            } catch (e: IOException) {
                return abortDirectory(staging, e.message)
            }
            val wanted = expected[child.name] ?: continue
            if (digest != wanted) {
                return abortDirectory(staging, "checksum mismatch: ${child.name}")
            }
        }
        // SHA256SUMS itself is part of the directory the CLI verifies later.
        try {
            File(staging, SUMS_FILE).writeText(readText(tree, sumsDocument))
        } catch (e: IOException) {
            return abortDirectory(staging, e.message)
        }

        val destination = File(destinationRoot, name)
        if (!staging.renameTo(destination)) {
            return abortDirectory(staging, "could not move the import into ${destination.path}")
        }
        return TransferResult.Success(name)
    }

    private fun readLocalSums(sourceDir: File): List<com.termux.app.domain.backup.SumEntry>? {
        return try {
            SafBackupManifest.parseSums(File(sourceDir, SUMS_FILE).readText())
        } catch (e: IOException) {
            null
        } catch (e: InvalidBackupException) {
            null
        }
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

    private companion object {
        const val SUMS_FILE = "SHA256SUMS"
        const val MANIFEST_FILE = "manifest.json"
        const val FORMAT_VERSION = 1
        const val TEMP_PREFIX = ".tmp-import-"
        const val BUFFER_SIZE = 8 * 1024
    }
}
