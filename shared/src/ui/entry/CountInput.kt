package app.servicecalendar.ui.entry

/** Result of parsing a whole-number text field (quantity or scheduled count). */
internal sealed interface CountInput {
    /** Blank input. */
    data object Empty : CountInput

    class Ok(val value: Int) : CountInput

    class Invalid(val message: String) : CountInput
}

/** Blank is [CountInput.Empty]; otherwise digits only and no Int overflow. */
internal fun parseCountInput(text: String): CountInput {
    val input = text.trim()
    if (input.isEmpty()) return CountInput.Empty
    if (input.startsWith('-')) return CountInput.Invalid(EntryStrings.ERROR_NEGATIVE)
    if (!input.all { it in '0'..'9' }) return CountInput.Invalid(EntryStrings.ERROR_NOT_INTEGER)
    val value = input.toIntOrNull() ?: return CountInput.Invalid(EntryStrings.ERROR_QUANTITY_TOO_LARGE)
    return CountInput.Ok(value)
}
