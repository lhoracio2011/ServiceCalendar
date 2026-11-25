package app.servicecalendar.ui.entry

import app.servicecalendar.ui.calendar.CalendarStrings
import kotlinx.datetime.LocalDate

internal object EntryStrings {
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val BACK = "←"
    const val LOADING = "Loading…"
    const val UNEXPECTED_ERROR = "Something went wrong. Please try again."

    const val EDITOR_NO_PROCEDURES =
        "This place has no procedures with prices yet. Add them in Settings → Procedures."
    const val PLACE_NOT_FOUND = "Place not found."
    const val QUANTITY = "Quantity"
    const val PRICE = "Price"
    const val LINE_TOTAL = "Line total"
    const val PLACE_TOTAL = "Place total"
    const val DAY_TOTAL = "Day total"
    const val TOTAL_UNAVAILABLE = "—"
    const val ARCHIVED_PROCEDURE = "archived procedure"
    const val DISCARD_TITLE = "Discard Changes?"
    const val DISCARD_TEXT = "Unsaved changes will be lost."
    const val DISCARD_CONFIRM = "Discard Changes"
    const val QUANTITY_PLACEHOLDER = "0"
    const val DISCARD_KEEP_EDITING = "Keep Editing"
    const val STALE_TITLE = "Data Changed"
    const val STALE_TEXT =
        "The local data changed after this day was opened (for example, after a backup restore). " +
            "Unsaved changes cannot be applied. Reload the day to see the current data."
    const val STALE_RELOAD = "Reload"
    const val RESTORED_TITLE = "Data Restored"
    const val RESTORED_TEXT =
        "Local data was replaced with data from the backup. Unsaved changes for this day were discarded, " +
            "and the restored data is shown."
    const val RESTORED_OK = "OK"
    const val RESTORING = "Restoring from a backup. Editing is temporarily unavailable."
    const val CLOSE = "Close"
    const val RETRY = "Try Again"

    const val ERROR_NEGATIVE = "Quantity cannot be negative"
    const val ERROR_NOT_INTEGER = "Enter a whole number"
    const val ERROR_QUANTITY_TOO_LARGE = "Number is too large"
    const val ERROR_LINE_TOO_LARGE = "Line total is too large"
    const val ERROR_TOTAL_TOO_LARGE = "Total is too large. Reduce the quantity."
    const val SCHEDULED_LABEL = "Scheduled (A)"
    const val COMPLETED_LABEL = "Done (R)"
    const val PERCENT_LABEL = "%"
    const val ERROR_SCHEDULED_REJECTED = "This number cannot be saved"
    const val ERROR_ROW_REJECTED = "This quantity cannot be saved"

    const val PICKER_TITLE = "Choose Place"
    const val PICKER_EMPTY =
        "No active places yet. Add a place in Settings → Places."

    fun scheduledDescription(): String = "Scheduled procedures (A)"
    fun summaryDescription(completed: String, percent: String) = "Done (R): $completed. Percent done: $percent"
    fun editorTitle(placeName: String) = "Day entry: $placeName"
    fun pickerSubtitle(date: LocalDate) = formatEntryDate(date)
    fun priceDescription(procedure: String, price: String) = "Price of $procedure: $price"
    fun quantityDescription(procedure: String) = "$QUANTITY: $procedure"
    fun lineTotalDescription(procedure: String, total: String) = "$LINE_TOTAL $procedure: $total"
    fun placeTotalDescription(place: String, total: String) = "$PLACE_TOTAL $place: $total"
    fun dayTotalDescription(total: String) = "$DAY_TOTAL: $total"
    fun pickPlaceDescription(name: String, address: String?) =
        if (address.isNullOrBlank()) "Place $name" else "Place $name, $address"
    fun pickPlaceAction(name: String) = "Open entry for place $name"
}

internal fun formatEntryDate(date: LocalDate): String = CalendarStrings.dayHeading(date)
