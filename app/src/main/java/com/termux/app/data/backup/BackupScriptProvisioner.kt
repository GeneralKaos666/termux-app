package com.termux.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Read access to the `termux-backup` assets packaged in the APK. */
interface BackupAssetSource {
    /** Relative asset paths under the `termux-backup/` asset root, including `VERSION`. */
    fun files(): List<String>

    /** Reads the asset at [path], relative to the `termux-backup/` asset root. */
    fun read(path: String): ByteArray
}

/** [BackupAssetSource] backed by the APK assets rooted at `termux-backup/`. */
class ApkAssetSource(private val context: Context) : BackupAssetSource {

    override fun files(): List<String> {
        val paths = mutableListOf<String>()
        for (name in context.assets.list("termux-backup") ?: emptyArray()) {
            if (name == "lib") {
                for (script in context.assets.list("termux-backup/lib") ?: emptyArray()) {
                    paths += "lib/$script"
                }
            } else {
                paths += name
            }
        }
        return paths
    }

    override fun read(path: String): ByteArray =
        context.assets.open("termux-backup/$path").use { it.readBytes() }
}

/** Outcome of [BackupScriptProvisioner.provision]. */
sealed interface ProvisionResult {
    /** The installed version already matches the bundled one. */
    data object UpToDate : ProvisionResult

    /** The bundle was (re)installed at the given version. */
    data class Installed(val version: String) : ProvisionResult

    /** The install failed; the message names the path involved. */
    data class Failed(val message: String) : ProvisionResult
}

/**
 * Installs the `termux-backup` CLI scripts from APK assets into the Termux prefix.
 *
 * The whole tree is staged in a temp directory — inside [installDir] when it already
 * exists, so an unwritable install dir fails the install, otherwise next to it — and
 * `.app-version` is written last inside the staged tree. The staged directory is then
 * swapped into place by rename, so a half-written copy can never replace a good
 * install. The `bin` symlink is refreshed only as an explicit effect of [provision],
 * never from [isCurrent] or [bundledVersion].
 */
class BackupScriptProvisioner(
    private val assets: BackupAssetSource,
    private val installDir: File,
    private val binLink: File,
) : BackupProvisioner {

    private val versionFile: File get() = File(installDir, VERSION_FILE)

    /** Version string from the bundled assets, or null when it cannot be read. */
    fun bundledVersion(): String? = try {
        String(assets.read(VERSION_ASSET)).trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** True when the installed version matches the bundled one and the dispatcher is runnable. */
    fun isCurrent(): Boolean {
        val bundled = bundledVersion() ?: return false
        val dispatcher = File(installDir, DISPATCHER_ASSET)
        return try {
            versionFile.exists() &&
                versionFile.readText().trim() == bundled &&
                dispatcher.exists() &&
                dispatcher.canExecute()
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Installs or refreshes the script tree, or reports [ProvisionResult.UpToDate]
     * when [force] is false and the installed version matches the bundled one.
     * Never throws: any failure is reported as [ProvisionResult.Failed].
     */
    override fun provision(force: Boolean): ProvisionResult {
        if (!force && isCurrent()) {
            // The install itself is fine, but the bin link may have gone missing or
            // stale (e.g. a deleted or repurposed $PREFIX/bin); re-assert it without
            // rewriting the install so the command self-heals on the next provision.
            return try {
                binLink.absoluteFile.parentFile?.mkdirs()
                refreshLink(File(installDir, DISPATCHER_ASSET).absolutePath)
                ProvisionResult.UpToDate
            } catch (e: IOException) {
                ProvisionResult.Failed("$binLink: ${e.message}")
            } catch (e: SecurityException) {
                ProvisionResult.Failed("$binLink: ${e.message}")
            }
        }
        val version = bundledVersion()
            ?: return ProvisionResult.Failed("$installDir: cannot read bundled VERSION asset")
        return try {
            installInto(version)
        } catch (e: IOException) {
            ProvisionResult.Failed("$installDir: ${e.message}")
        } catch (e: SecurityException) {
            ProvisionResult.Failed("$installDir: ${e.message}")
        }
    }

    private fun installInto(version: String): ProvisionResult {
        val parent = installDir.absoluteFile.parentFile
            ?: return ProvisionResult.Failed("$installDir: no parent directory")
        // Stage inside the existing dir: creating the staging dir there proves the
        // install dir itself is writable before anything is swapped.
        val stagedInside = installDir.exists()
        val stagingRoot = if (stagedInside) installDir else parent
        if (stagedInside && !installDir.isDirectory) {
            return ProvisionResult.Failed("$installDir: not a directory")
        }
        if (!stagedInside && !parent.isDirectory && !parent.mkdirs()) {
            return ProvisionResult.Failed("$parent: cannot create directory")
        }
        var staging = File(stagingRoot, STAGING_PREFIX + suffix())
        if (!staging.mkdir()) {
            return ProvisionResult.Failed("$stagingRoot: cannot create staging directory")
        }
        val previous = File(parent, OLD_PREFIX + suffix())
        try {
            for (path in assets.files()) {
                val target = File(staging, path)
                target.parentFile?.mkdirs()
                target.writeBytes(assets.read(path))
                if (path == DISPATCHER_ASSET) makeExecutable(target)
            }
            // Written last so its presence marks a complete staged tree.
            File(staging, VERSION_FILE).writeText(version)

            // Swap: move the old install aside, put the staged tree in its place.
            previous.deleteRecursively()
            if (stagedInside && !installDir.renameTo(previous)) {
                return ProvisionResult.Failed("$installDir: cannot move old install aside")
            }
            val staged = if (stagedInside) File(previous, staging.name) else staging
            if (!staged.renameTo(installDir)) {
                // Put the old install back so a broken tree never takes its place.
                if (stagedInside) previous.renameTo(installDir)
                return ProvisionResult.Failed("$installDir: cannot move staged install into place")
            }
            previous.deleteRecursively()
            // The staged tree has become [installDir]; point [staging] away so the
            // cleanup below cannot delete the fresh install.
            staging = File(previous, CLEANED_UP)

            binLink.absoluteFile.parentFile?.mkdirs()
            refreshLink(File(installDir, DISPATCHER_ASSET).absolutePath)
            return ProvisionResult.Installed(version)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Replaces [binLink] with a symlink to [target], removing any stale link first.
     *
     * `java.nio.file` arrived in API 26, so older devices use [android.system.Os] instead.
     */
    @SuppressLint("NewApi")
    private fun refreshLink(target: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.deleteIfExists(binLink.toPath())
            Files.createSymbolicLink(binLink.toPath(), File(target).toPath())
        } else {
            if (!binLink.delete() && binLink.exists()) {
                throw IOException("${binLink.path}: cannot remove the old link")
            }
            Os.symlink(target, binLink.path)
        }
    }

    /** Makes [file] mode 755: owner rwx, group and other r-x. */
    @SuppressLint("NewApi")
    private fun makeExecutable(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
                )
                return
            } catch (_: UnsupportedOperationException) {
                // Fall through to the portable calls below.
            }
        } else if (runCatching { Os.chmod(file.path, 0x1ED) }.isSuccess) {
            return
        }
        file.setReadable(true, false)
        file.setExecutable(true, false)
    }

    private fun suffix(): String = UUID.randomUUID().toString()

    private companion object {
        const val VERSION_ASSET = "VERSION"
        const val VERSION_FILE = ".app-version"
        const val DISPATCHER_ASSET = "termux-backup"
        const val STAGING_PREFIX = ".tmp-"
        const val OLD_PREFIX = ".old-"
        const val CLEANED_UP = ".deleted"
    }
}
