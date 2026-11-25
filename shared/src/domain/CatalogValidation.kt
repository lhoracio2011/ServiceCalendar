package app.servicecalendar.domain

import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.money.MinorAmount

private const val ARGB_MAX = 4_294_967_295L

// Place colours are not unique: a new place gets a random free palette colour, or a repeat when none is left.
fun validateCatalogName(raw: String, field: String = "name"): String {
    val name = raw.trim()
    if (name.isEmpty()) throw ValidationError(field, "name cannot be empty")
    if (name.length > CatalogLimits.MAX_NAME_LENGTH) throw ValidationError(field, nameTooLongReason())
    return name
}

fun validateRate(raw: Long, field: String = "rate"): MinorAmount {
    if (raw < 0) throw ValidationError(field, "price cannot be negative")
    return MinorAmount(raw)
}

fun nameTooLongReason(): String = "name cannot be longer than ${CatalogLimits.MAX_NAME_LENGTH} characters"

fun addressTooLongReason(): String = "address cannot be longer than ${CatalogLimits.MAX_ADDRESS_LENGTH} characters"

fun validatePlaceAddress(raw: String?, field: String = "address"): String? {
    val address = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (address.length > CatalogLimits.MAX_ADDRESS_LENGTH) throw ValidationError(field, addressTooLongReason())
    return address
}

fun validatePlaceColor(raw: Long): Long {
    if (raw !in 0..ARGB_MAX) throw ValidationError("color", "color is outside the ARGB range")
    return raw
}

class ValidatedPlaceDraft(
    val id: Long?,
    val name: String,
    val address: String?,
    val color: Long,
    val rates: Map<Long, MinorAmount>,
)

fun validatePlaceDraft(draft: PlaceDraft): ValidatedPlaceDraft = ValidatedPlaceDraft(
    id = draft.id,
    name = validateCatalogName(draft.name),
    address = validatePlaceAddress(draft.address),
    color = validatePlaceColor(draft.color),
    rates = draft.rates.mapValues { (procedureId, raw) -> validateRate(raw, "rate.$procedureId") },
)
