package app.servicecalendar.ui.backup

import app.servicecalendar.backup.BackupErrorKind
import app.servicecalendar.backup.BackupOperation
import app.servicecalendar.backup.DatasetCounts
import app.servicecalendar.backup.RestoreReport
import app.servicecalendar.ui.calendar.CalendarStrings
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal object BackupStrings {
    const val SECTION_LABEL = "Backup"

    const val UNAVAILABLE = "Backup is not available on this device"
    const val UNAVAILABLE_HINT = "Your entries are stored on the device and work as usual."

    const val ACCOUNT_LABEL = "Google Account"
    const val NOT_CONNECTED = "Google Drive is not connected"
    const val NOT_CONNECTED_HINT =
        "Connect a Google account to back up your catalog and entries to Google Drive every day and restore them after a reinstall."
    const val CONNECT = "Connect Google Drive"
    const val BACK_UP_NOW = "Back Up Now"
    const val DISCONNECT = "Disconnect Account"
    const val LAST_BACKUP_LABEL = "Last backup"
    const val NO_BACKUP_YET = "No backup yet"

    const val STATUS_ACTIVE = "Backup is on"
    const val STATUS_ACTIVE_HINT = "The backup is updated daily when a connection is available."
    const val STATUS_SEARCH = "Looking for an existing backup"
    const val STATUS_SEARCH_HINT = "Nothing is uploaded to Drive until the search finishes."
    const val STATUS_SEARCH_FAILED = "Could not check for an existing backup"
    const val STATUS_SEARCH_FAILED_HINT = "Nothing is uploaded to Drive until the check succeeds."
    const val RETRY_SEARCH = "Retry"

    const val CHOICE_TITLE = "A Backup Already Exists on Google Drive"
    const val CHOICE_TEXT =
        "Choose what to do with it. Until you decide, nothing is uploaded to Drive and your local entries stay unchanged."
    const val CHOICE_POSTPONED = "Decision postponed. You can keep working locally, and the backup on Drive is kept."
    const val RESTORE_COPY = "Restore Backup"
    const val REPLACE_COPY = "Replace Backup with Local Data"
    const val DECIDE_LATER = "Decide Later"

    const val REPLACE_TITLE = "Replace Backup on Drive?"
    const val REPLACE_TEXT =
        "The backup currently on Google Drive will be lost: it will be replaced with data from this device. " +
            "The previous backup cannot be recovered afterward."
    const val REPLACE_CONFIRM = "Replace Backup"
    const val CANCEL = "Cancel"

    const val DISCONNECT_TITLE = "Disconnect Google Account?"
    const val DISCONNECT_TEXT =
        "Daily backups to Google Drive will stop. Entries on this device and the backup on Drive " +
            "will stay unchanged. To back up again, connect the account once more."
    const val DISCONNECT_CONFIRM = "Disconnect"
    const val DISMISS = "Close"

    const val RESTORE_TITLE = "Restore Backup?"
    const val RESTORE_WARNING =
        "All local data on this device and unsaved changes (drafts) in open editors will be replaced with the backup data."
    const val RESTORE_SAME = "The backup data matches the local data, but drafts will still be discarded."
    const val RESTORE_CONFIRM = "Replace and Restore"
    const val RESTORE_COPY_HEADING = "In the backup"
    const val RESTORE_LOCAL_HEADING = "Will be replaced on this device"
    const val RESTORE_CURRENCY_DIFFERS = "The backup currency differs from the current one."

    const val RESTORING = "Restoring backup…"
    const val RESTORING_HINT = "Editing is temporarily locked. Do not close the app."
    const val PREPARING = "Downloading and checking the backup…"
    const val SEARCHING = "Looking for a backup on Drive…"
    const val UPLOADING = "Saving the backup to Drive…"
    const val CHECKING = "Checking the backup status…"

    const val NOTICE_RESTORED = "Backup restored. The calendar, reports and catalog show the restored data."
    const val NOTICE_BACKUP_SAVED = "Backup saved to Google Drive."
    const val NOTICE_STALE = "The backup or account has changed. Prepare the restore again."
    const val NOTICE_REPLACE_UNAVAILABLE = "The backup cannot be replaced right now. Please try again."

    const val INVALID_RESTORE = "The backup is damaged or failed validation, so it was not restored. Your data is unchanged."
    const val INVALID_UPLOAD = "The data on this device failed a safety check, so the backup was not saved to Google Drive. Your data is unchanged."

    const val RETRY = "Retry"
    const val RESTORE_ACCESS = "Restore Access"
    const val RETRY_RESTORE = "Check Backup Again"

    /** [uploading] tells the two sources of [BackupErrorKind.INVALID_COPY] apart: a failed upload of local data, or a Drive copy rejected on restore. */
    fun errorText(kind: BackupErrorKind, invalidDetail: String? = null, uploading: Boolean = false): String = when (kind) {
        BackupErrorKind.NETWORK -> "Cannot reach Google Drive. Your data on the device is safe. Check your internet connection and try again."
        BackupErrorKind.DRIVE_UNAVAILABLE -> "Google Drive is temporarily unavailable. Please try again a bit later."
        BackupErrorKind.NEEDS_USER_ACTION -> "Access to Google Drive needs to be restored."
        BackupErrorKind.AUTH_FAILED -> "Could not sign in to your Google account. Please try again."
        BackupErrorKind.FORBIDDEN ->
            "Google Drive denied access to the backup. Your data on the device is safe. Check that this app may use Google Drive and try again."
        BackupErrorKind.STORAGE_FULL ->
            "Your Google Drive storage is full, so the backup was not saved. Your data on the device is safe. Free up space and try again."
        BackupErrorKind.STORAGE_BUSY -> "The data is being updated. Please try again in a minute."
        BackupErrorKind.INVALID_COPY ->
            if (uploading) {
                INVALID_UPLOAD
            } else {
                INVALID_RESTORE + (invalidDetail?.let { " $it" } ?: "")
            }
        BackupErrorKind.TOO_LARGE -> "The backup is too large to process."
        BackupErrorKind.COPY_MISSING -> "The backup file is gone from Google Drive. A new search is needed."
        BackupErrorKind.UNEXPECTED -> "An unexpected error occurred. Your data is unchanged. Please try again."
    }

    fun operationText(operation: BackupOperation): String = when (operation) {
        BackupOperation.CHECKING -> CHECKING
        BackupOperation.SEARCHING -> SEARCHING
        BackupOperation.UPLOADING -> UPLOADING
        BackupOperation.PREPARING_RESTORE -> PREPARING
        BackupOperation.RESTORING -> RESTORING
    }

    fun dateTimeText(at: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = at.toLocalDateTime(zone)
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "${CalendarStrings.dateText(local.date)}, $hh:$mm"
    }

    fun copyCreatedText(createdAt: String, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        runCatching { dateTimeText(Instant.parse(createdAt), zone) }.getOrDefault(createdAt)

    fun lastBackupText(at: Instant?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        if (at == null) NO_BACKUP_YET else dateTimeText(at, zone)

    fun accountDescription(label: String): String = "$ACCOUNT_LABEL: $label"

    fun copyCreatedLine(createdAt: String, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        "Backup created: ${copyCreatedText(createdAt, zone)}"

    fun countsText(counts: DatasetCounts): String = listOf(
        plural(counts.procedures, "procedure", "procedures"),
        plural(counts.places, "place", "places"),
        plural(counts.placeRates, "price", "prices"),
        plural(counts.workEntries, "day entry", "day entries"),
        plural(counts.workLines, "procedure line", "procedure lines"),
    ).joinToString(", ")

    fun restoreCopyLine(report: RestoreReport): String = "$RESTORE_COPY_HEADING: ${countsText(report.backup)}"

    fun restoreLocalLine(report: RestoreReport): String = "$RESTORE_LOCAL_HEADING: ${countsText(report.local)}"

    fun plural(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"
}
