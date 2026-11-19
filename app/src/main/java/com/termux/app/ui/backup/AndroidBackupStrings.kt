package com.termux.app.ui.backup

import android.content.res.Resources
import com.termux.R

/** [BackupStrings] backed by the app's string resources. */
class AndroidBackupStrings(private val resources: Resources) : BackupStrings {

    override fun transferRunning(): String = resources.getString(R.string.backup_transfer_running)

    override fun created(name: String?, size: String?, packages: Int?): String =
        if (name == null || size == null || packages == null) resources.getString(R.string.backup_created)
        else resources.getQuantityString(R.plurals.backup_created_detail, packages, name, size, packages)

    override fun restored(name: String?, safety: String?): String = when {
        name == null -> resources.getString(R.string.backup_restored)
        safety != null -> resources.getString(R.string.backup_restored_safety, name, safety)
        else -> resources.getString(R.string.backup_restored_detail, name)
    }

    override fun deleted(name: String?): String =
        if (name == null) resources.getString(R.string.backup_deleted)
        else resources.getString(R.string.backup_deleted_detail, name)

    override fun pruned(removed: Int?, kept: Int?): String =
        if (removed == null || kept == null) resources.getString(R.string.backup_pruned)
        else resources.getString(R.string.backup_pruned_detail, removed, kept)

    override fun verified(name: String?, valid: Boolean?): String = when {
        name == null || valid == null -> resources.getString(R.string.backup_verified)
        valid -> resources.getString(R.string.backup_verified_detail, name)
        else -> resources.getString(R.string.backup_verification_failed, name)
    }

    override fun commandFailed(): String = resources.getString(R.string.backup_command_failed)

    override fun done(): String = resources.getString(R.string.backup_done)
}
