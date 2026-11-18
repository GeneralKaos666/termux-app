package com.termux.app.domain.backup

/**
 * One invocation of the `termux-backup` CLI, modelled as data rather than a process.
 *
 * The command never builds a shell string: [argv] returns the exact element list handed to the
 * process, so a label or a path containing spaces stays one argument.
 */
sealed interface BackupCommand {

    /** The CLI subcommand ([BackupCommand.Backup] is `backup`, [ListBackups] is `list`, ...). */
    val subcommand: String

    /**
     * The full argument vector: the script, the subcommand and its options, then the global
     * `--json` and `--progress-file <progressFile>` tail, in that order.
     *
     * `--path` is never emitted; the app and the CLI agree on the backup root elsewhere.
     *
     * @param scriptPath absolute path of the bundled `termux-backup` script.
     * @param progressFile path the CLI appends JSONL progress events to.
     */
    fun argv(scriptPath: String, progressFile: String): List<String>

    /** Create a backup of packages, `$HOME` and `$PREFIX/etc`. */
    data class Backup(
        val label: String?,
        val compression: String,
        val keep: Int?,
        val excludeSecrets: Boolean,
        val includeHome: Boolean,
        val includeConfig: Boolean,
    ) : BackupCommand {

        override val subcommand: String = "backup"

        override fun argv(scriptPath: String, progressFile: String): List<String> = argvOf(
            scriptPath,
            progressFile,
            buildList {
                add(subcommand)
                if (label != null && label.isNotEmpty()) {
                    add("--label")
                    add(label)
                }
                add("--compression")
                add(compression)
                if (keep != null) {
                    add("--keep")
                    add(keep.toString())
                }
                if (excludeSecrets) add("--exclude-secrets")
                if (!includeHome) add("--no-home")
                if (!includeConfig) add("--no-config")
            },
        )
    }

    /** Restore the named backup. The CLI asks for confirmation unless `--yes` is passed. */
    data class Restore(
        val name: String,
        val safety: Boolean,
        val packages: Boolean,
    ) : BackupCommand {

        override val subcommand: String = "restore"

        override fun argv(scriptPath: String, progressFile: String): List<String> = argvOf(
            scriptPath,
            progressFile,
            buildList {
                add(subcommand)
                add("--name")
                add(name)
                add("--yes")
                if (!safety) add("--no-safety")
                if (!packages) add("--no-packages")
            },
        )
    }

    /** List the available backups. */
    data object ListBackups : BackupCommand {

        override val subcommand: String = "list"

        override fun argv(scriptPath: String, progressFile: String): List<String> =
            argvOf(scriptPath, progressFile, listOf(subcommand))
    }

    /** Report the configuration and storage state. */
    data object Status : BackupCommand {

        override val subcommand: String = "status"

        override fun argv(scriptPath: String, progressFile: String): List<String> =
            argvOf(scriptPath, progressFile, listOf(subcommand))
    }

    /** Verify the named backup's checksums. */
    data class Verify(val name: String) : BackupCommand {

        override val subcommand: String = "verify"

        override fun argv(scriptPath: String, progressFile: String): List<String> =
            argvOf(scriptPath, progressFile, listOf(subcommand, "--name", name))
    }

    /** Delete the named backup. `--yes` skips the CLI's own confirmation. */
    data class Delete(val name: String) : BackupCommand {

        override val subcommand: String = "delete"

        override fun argv(scriptPath: String, progressFile: String): List<String> =
            argvOf(scriptPath, progressFile, listOf(subcommand, "--name", name, "--yes"))
    }

    /** Keep only the newest [keep] backups. */
    data class Prune(val keep: Int) : BackupCommand {

        override val subcommand: String = "prune"

        override fun argv(scriptPath: String, progressFile: String): List<String> =
            argvOf(scriptPath, progressFile, listOf(subcommand, "--keep", keep.toString()))
    }

    companion object {

        /**
         * The label rule the CLI applies: letters, digits, dot, underscore and dash only, because
         * the label becomes part of the backup directory name.
         */
        private val LABEL_PATTERN = Regex("[A-Za-z0-9._-]+")

        /**
         * Why [label] cannot be used as a backup label, or `null` when it can.
         */
        fun labelError(label: String): String? =
            if (LABEL_PATTERN.matches(label)) {
                null
            } else {
                "Use letters, digits, dot, underscore or dash."
            }
    }
}

/**
 * The shared argument vector shape: script, [arguments], then the global JSON and progress tail.
 */
private fun argvOf(
    scriptPath: String,
    progressFile: String,
    arguments: List<String>,
): List<String> = buildList {
    add(scriptPath)
    addAll(arguments)
    add("--json")
    add("--progress-file")
    add(progressFile)
}
