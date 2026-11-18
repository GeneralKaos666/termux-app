package com.termux.app.data.backup

/** Port the backup screen uses to make sure the bundled script is installed. */
interface BackupProvisioner {

    /** Installs the script when missing or outdated; [force] rewrites it unconditionally. */
    fun provision(force: Boolean = false): ProvisionResult
}
