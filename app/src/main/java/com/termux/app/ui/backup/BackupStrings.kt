package com.termux.app.ui.backup

/**
 * The user-visible text of the backup screen, so the view model owns no literals and its tests name
 * the string they expect instead of matching copy.
 *
 * The `null` cases mean "the command printed no JSON to take the values from", and select the plain
 * message instead of the one carrying details.
 */
interface BackupStrings {
    fun transferRunning(): String
    fun created(name: String?, size: String?, packages: Int?): String
    fun restored(name: String?, safety: String?): String
    fun deleted(name: String?): String
    fun pruned(removed: Int?, kept: Int?): String
    fun verified(name: String?, valid: Boolean?): String
    fun commandFailed(): String
    fun done(): String
}
