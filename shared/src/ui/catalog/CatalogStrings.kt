package app.servicecalendar.ui.catalog

import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.money.localeDecimalSeparator

internal object CatalogStrings {
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val DELETE = "Delete"
    const val DIALOG_OK = "OK"
    const val PROCEDURE_EDIT_ICON = "✎"
    const val PROCEDURE_DELETE_ICON = "✕"
    const val COLOR_COLLAPSE_ICON = "▲"
    const val COLOR_EXPAND_ICON = "▼"
    const val BACK = "←"
    const val ADD = "+"
    const val LOADING = "Loading…"
    const val UNEXPECTED_ERROR = "Something went wrong. Please try again."

    const val ADD_PROCEDURE = "Add New Procedure"
    const val PROCEDURE_RENAME_TITLE = "Rename Procedure"
    const val PROCEDURE_NAME = "Procedure Name"
    const val PROCEDURE_RENAME = "Rename"
    const val PROCEDURE_REMOVE_TITLE = "Delete Procedure?"
    fun procedureRemoveText(name: String) =
        "The procedure \"$name\" will be deleted. If it is already used in entries, it will only be hidden and the history will be kept."

    const val PLACES_EMPTY = "No places yet. Tap \"+\" to add the first one."
    const val ADD_PLACE = "Add Place"

    const val PLACE_NEW_TITLE = "New Place"
    const val PLACE_EDIT_TITLE = "Place"
    const val PLACE_NAME = "Name"
    const val PLACE_ADDRESS = "Address"
    const val PLACE_COLOR = "Color"
    const val PLACE_CHOOSE_COLOR = "Choose Color"
    const val PLACE_PROCEDURES = "Procedures and Prices"
    const val PLACE_NO_PROCEDURES = "The procedure list is empty. Add them in Settings → Procedures."
    const val PLACE_PRICE = "Price"
    const val PLACE_REMOVE = "Delete Place"
    const val PLACE_REMOVE_TITLE = "Delete Place?"
    fun placeRemoveText(name: String) =
        "The place \"$name\" will be deleted along with its prices. If it already has entries, it will only be hidden and the history will be kept."
    const val PLACE_NOT_FOUND = "Place not found."
    const val PLACE_GONE_AFTER_RESTORE =
        "This place no longer exists after the backup was restored. Unsaved changes were discarded. Close the editor."
    const val PLACE_RESTORED_TEXT =
        "Local data was replaced with data from the backup. Unsaved changes to this place were discarded and the restored data is shown."

    const val RESTORED_TITLE = "Data Restored"
    const val PROCEDURES_RESTORED_TEXT =
        "Local data was replaced with data from the backup. Unsaved changes in the dialog were discarded and the restored list is shown."
    const val RESTORED_OK = "OK"
    const val RESTORING = "Restoring from a backup. Editing is temporarily unavailable."
    const val STALE_TITLE = "Data Changed"
    const val STALE_TEXT =
        "The local data changed after this was opened (for example, after a backup restore). " +
            "Unsaved changes cannot be applied. Reload to see the current data."
    const val STALE_RELOAD = "Reload"
    const val CLOSE = "Close"

    const val ERROR_NAME_REQUIRED = "Enter a name"
    fun nameTooLong() = "Name is too long: ${CatalogLimits.MAX_NAME_LENGTH} characters max"
    fun addressTooLong() = "Address is too long: ${CatalogLimits.MAX_ADDRESS_LENGTH} characters max"
    const val ERROR_PRICE_REQUIRED = "Enter a price"
    fun pricePlaceholder(decimalSeparator: Char = localeDecimalSeparator()) = "0${decimalSeparator}00"

    fun priceInvalid(decimalSeparator: Char = localeDecimalSeparator()) =
        "Invalid price, for example 12${decimalSeparator}50"
    const val ERROR_COLOR_INVALID = "Choose a color from the palette"
}
