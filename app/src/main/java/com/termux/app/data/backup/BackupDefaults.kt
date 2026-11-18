package com.termux.app.data.backup

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Fixed locations shared by the backup runner, the provisioner and the transfer coordinator.
 * Everything hangs off [TermuxConstants] so a forked package name keeps working.
 */
object BackupDefaults {

    /** Directory holding the progress file, the run lock and the transfer state. */
    val configDir: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/termux-backup")

    /** JSONL file the script appends progress events to. */
    val progressFile: File = File(configDir, "progress.jsonl")

    /** Lock that allows only one backup or restore at a time. */
    val lockFile: File = File(configDir, "run.lock")

    /** State of an in-flight SAF export or import. */
    val transferStateFile: File = File(configDir, "transfer.json")

    /** Where the provisioned script tree lives. */
    val scriptInstallDir: File = File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH, "termux-backup")

    /** The `termux-backup` command as it is found on `$PATH`. */
    val scriptBinLink: File = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "termux-backup")

    /** Entry point of the provisioned script. */
    val scriptPath: File = File(scriptInstallDir, "termux-backup")

    /** How often the UI re-reads the progress file while a run is in flight. */
    const val pollIntervalMs: Long = 500L
}
