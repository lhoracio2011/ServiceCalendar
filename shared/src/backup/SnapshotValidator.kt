package app.servicecalendar.backup

import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.parseStorageDate
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Limits guard against corrupt or hostile files, not users; they are deliberately far above real data volumes. */
object SnapshotLimits {
    const val MAX_PROCEDURES: Int = CatalogLimits.MAX_PROCEDURES
    const val MAX_PLACES: Int = CatalogLimits.MAX_PLACES
    const val MAX_PLACE_RATES: Int = CatalogLimits.MAX_PLACE_RATES
    const val MAX_WORK_ENTRIES: Int = CatalogLimits.MAX_WORK_ENTRIES
    const val MAX_WORK_LINES: Int = CatalogLimits.MAX_WORK_LINES

    const val MAX_NAME_LENGTH: Int = CatalogLimits.MAX_NAME_LENGTH

    const val MAX_ADDRESS_LENGTH: Int = CatalogLimits.MAX_ADDRESS_LENGTH

    const val MAX_JSON_CHARS: Int = 64 * 1024 * 1024

    const val MAX_JSON_DEPTH: Int = 32
}

enum class SnapshotInvalidReason {
    MALFORMED_JSON,

    TOO_LARGE,

    MISSING_FIELD,

    WRONG_FIELD_TYPE,

    NEWER_VERSION,

    UNSUPPORTED_VERSION,

    INVALID_CREATED_AT,

    INVALID_CURRENCY,

    TOO_MANY_ROWS,

    INVALID_ID,

    BLANK_NAME,

    NAME_TOO_LONG,

    DUPLICATE_ID,

    INVALID_COLOR,

    DANGLING_REFERENCE,

    DUPLICATE_PAIR,

    NEGATIVE_AMOUNT,

    INVALID_QUANTITY,

    INVALID_DATE,

    AMOUNT_OVERFLOW,

    INVALID_SCHEDULED_COUNT,

    EMPTY_ENTRY,
}

fun SnapshotInvalidReason.userMessage(): String = when (this) {
    SnapshotInvalidReason.MALFORMED_JSON -> "The backup file is damaged or is not a backup from this app."
    SnapshotInvalidReason.TOO_LARGE -> "The backup file is too large to restore."
    SnapshotInvalidReason.MISSING_FIELD -> "The backup is missing required data."
    SnapshotInvalidReason.WRONG_FIELD_TYPE -> "The backup contains data in the wrong format."
    SnapshotInvalidReason.NEWER_VERSION -> "The backup was created by a newer version of the app. Update the app to restore it."
    SnapshotInvalidReason.UNSUPPORTED_VERSION -> "This backup version is not supported."
    SnapshotInvalidReason.INVALID_CREATED_AT -> "The backup has an invalid creation time."
    SnapshotInvalidReason.INVALID_CURRENCY -> "The backup has an invalid currency code."
    SnapshotInvalidReason.TOO_MANY_ROWS -> "The backup has too many records."
    SnapshotInvalidReason.INVALID_ID -> "The backup has an invalid record ID."
    SnapshotInvalidReason.BLANK_NAME -> "The backup has a procedure or place without a name."
    SnapshotInvalidReason.NAME_TOO_LONG -> "The backup has a name or address that is too long."
    SnapshotInvalidReason.DUPLICATE_ID -> "The backup has records with the same ID."
    SnapshotInvalidReason.INVALID_COLOR -> "The backup has a place with an invalid color."
    SnapshotInvalidReason.DANGLING_REFERENCE -> "The backup refers to a place, procedure or entry that does not exist."
    SnapshotInvalidReason.DUPLICATE_PAIR -> "The backup has a duplicate price, day entry or entry line."
    SnapshotInvalidReason.NEGATIVE_AMOUNT -> "The backup has a negative amount."
    SnapshotInvalidReason.INVALID_QUANTITY -> "The backup has an invalid procedure quantity."
    SnapshotInvalidReason.INVALID_DATE -> "The backup has an invalid entry date."
    SnapshotInvalidReason.AMOUNT_OVERFLOW -> "The amounts in the backup are too large."
    SnapshotInvalidReason.INVALID_SCHEDULED_COUNT -> "The backup has an invalid number of scheduled procedures."
    SnapshotInvalidReason.EMPTY_ENTRY -> "The backup has a day entry without procedures or a scheduled count."
}

sealed interface SnapshotValidationResult {
    data class Valid(val snapshot: BackupSnapshot) : SnapshotValidationResult

    data class Invalid(val reason: SnapshotInvalidReason, val detail: String) : SnapshotValidationResult {
        fun userMessage(): String = reason.userMessage()
    }
}

private const val INT_MAX = Int.MAX_VALUE.toLong()
private const val ARGB_MAX = 4_294_967_295L

private class Rejected(val reason: SnapshotInvalidReason, val detail: String) : Exception(detail)

private fun reject(reason: SnapshotInvalidReason, detail: String): Nothing = throw Rejected(reason, detail)

fun validateSnapshot(snapshot: BackupSnapshot): SnapshotValidationResult =
    try {
        check(snapshot)
        SnapshotValidationResult.Valid(snapshot)
    } catch (e: Rejected) {
        SnapshotValidationResult.Invalid(e.reason, e.detail)
    }

fun parseAndValidate(json: String, maxChars: Int = SnapshotLimits.MAX_JSON_CHARS): SnapshotValidationResult {
    if (json.length > maxChars) {
        return SnapshotValidationResult.Invalid(SnapshotInvalidReason.TOO_LARGE, "length ${json.length} > $maxChars")
    }
    if (nestingDepthExceeds(json, SnapshotLimits.MAX_JSON_DEPTH)) {
        return SnapshotValidationResult.Invalid(SnapshotInvalidReason.MALFORMED_JSON, "nesting too deep")
    }
    val root = try {
        SnapshotCodec.parseElement(json)
    } catch (e: Exception) {
        return SnapshotValidationResult.Invalid(SnapshotInvalidReason.MALFORMED_JSON, shortDetail(e))
    }
    if (root !is JsonObject) {
        return SnapshotValidationResult.Invalid(SnapshotInvalidReason.MALFORMED_JSON, "root is not an object")
    }
    val version = root["schemaVersion"]
    if (version == null) {
        return SnapshotValidationResult.Invalid(SnapshotInvalidReason.MISSING_FIELD, "missing field schemaVersion")
    }
    if (version is JsonPrimitive && !version.isString) {
        val number = version.content.toLongOrNull()
        if (number != null && number > BackupSnapshot.CURRENT_VERSION) {
            return SnapshotValidationResult.Invalid(
                SnapshotInvalidReason.NEWER_VERSION,
                "schemaVersion $number > ${BackupSnapshot.CURRENT_VERSION}",
            )
        }
    }
    val snapshot = try {
        SnapshotCodec.decodeElement(root)
    } catch (e: SerializationException) {
        val reason = if ("is required for type" in e.message.orEmpty()) {
            SnapshotInvalidReason.MISSING_FIELD
        } else {
            SnapshotInvalidReason.WRONG_FIELD_TYPE
        }
        return SnapshotValidationResult.Invalid(reason, shortDetail(e))
    }
    return validateSnapshot(snapshot)
}

private fun shortDetail(e: Exception): String =
    (e.message ?: e::class.simpleName ?: "error").lineSequence().first().take(200)

/** Guards the recursive parser against deeply nested input. */
private fun nestingDepthExceeds(text: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '{', '[' -> if (++depth > limit) return true
            '}', ']' -> if (depth > 0) depth--
        }
    }
    return false
}

private fun check(s: BackupSnapshot) {
    if (s.schemaVersion > BackupSnapshot.CURRENT_VERSION) {
        reject(SnapshotInvalidReason.NEWER_VERSION, "schemaVersion ${s.schemaVersion} > ${BackupSnapshot.CURRENT_VERSION}")
    }
    if (s.schemaVersion < BackupSnapshot.MIN_SUPPORTED_VERSION) reject(SnapshotInvalidReason.UNSUPPORTED_VERSION, "schemaVersion ${s.schemaVersion}")
    if (!isUtcTimestamp(s.createdAt)) reject(SnapshotInvalidReason.INVALID_CREATED_AT, "createdAt is not UTC ISO-8601")
    if (s.currencyCode.length != 3 || !s.currencyCode.all { it in 'A'..'Z' }) {
        reject(SnapshotInvalidReason.INVALID_CURRENCY, "currencyCode must be three letters A-Z")
    }

    limit("procedures", s.procedures.size, SnapshotLimits.MAX_PROCEDURES)
    limit("places", s.places.size, SnapshotLimits.MAX_PLACES)
    limit("placeRates", s.placeRates.size, SnapshotLimits.MAX_PLACE_RATES)
    limit("workEntries", s.workEntries.size, SnapshotLimits.MAX_WORK_ENTRIES)
    limit("workLines", s.workLines.size, SnapshotLimits.MAX_WORK_LINES)

    val procedureIds = HashSet<Long>(s.procedures.size * 2)
    for (p in s.procedures) {
        positiveId("procedures", p.id)
        checkName("procedures", p.id, p.name)
        if (!procedureIds.add(p.id)) reject(SnapshotInvalidReason.DUPLICATE_ID, "procedures: id ${p.id}")
    }

    val placeIds = HashSet<Long>(s.places.size * 2)
    for (p in s.places) {
        positiveId("places", p.id)
        checkName("places", p.id, p.name)
        if (p.address != null && p.address.length > SnapshotLimits.MAX_ADDRESS_LENGTH) {
            reject(SnapshotInvalidReason.NAME_TOO_LONG, "places: address of place ${p.id} is too long")
        }
        if (p.color !in 0..ARGB_MAX) reject(SnapshotInvalidReason.INVALID_COLOR, "places: color of place ${p.id}")
        if (!placeIds.add(p.id)) reject(SnapshotInvalidReason.DUPLICATE_ID, "places: id ${p.id}")
    }

    val ratePairs = HashSet<Pair<Long, Long>>(s.placeRates.size * 2)
    for (r in s.placeRates) {
        if (r.amountMinor < 0) {
            reject(SnapshotInvalidReason.NEGATIVE_AMOUNT, "placeRates: (${r.placeId}, ${r.procedureId})")
        }
        if (r.placeId !in placeIds) {
            reject(SnapshotInvalidReason.DANGLING_REFERENCE, "placeRates: place ${r.placeId} not found")
        }
        if (r.procedureId !in procedureIds) {
            reject(SnapshotInvalidReason.DANGLING_REFERENCE, "placeRates: procedure ${r.procedureId} not found")
        }
        if (!ratePairs.add(r.placeId to r.procedureId)) {
            reject(SnapshotInvalidReason.DUPLICATE_PAIR, "placeRates: (${r.placeId}, ${r.procedureId})")
        }
    }

    val entryIds = HashSet<Long>(s.workEntries.size * 2)
    val datePlacePairs = HashSet<Pair<String, Long>>(s.workEntries.size * 2)
    for (e in s.workEntries) {
        positiveId("workEntries", e.id)
        try {
            parseStorageDate(e.localDate)
        } catch (_: ValidationError) {
            reject(SnapshotInvalidReason.INVALID_DATE, "workEntries: entry ${e.id}")
        }
        if (e.placeId !in placeIds) {
            reject(SnapshotInvalidReason.DANGLING_REFERENCE, "workEntries: place ${e.placeId} not found")
        }
        val scheduled = e.scheduledCount
        if (scheduled != null && scheduled !in 0..INT_MAX) {
            reject(SnapshotInvalidReason.INVALID_SCHEDULED_COUNT, "workEntries: entry ${e.id}")
        }
        if (!entryIds.add(e.id)) reject(SnapshotInvalidReason.DUPLICATE_ID, "workEntries: id ${e.id}")
        if (!datePlacePairs.add(e.localDate to e.placeId)) {
            reject(SnapshotInvalidReason.DUPLICATE_PAIR, "workEntries: date and place of entry ${e.id}")
        }
    }

    val linePairs = HashSet<Pair<Long, Long>>(s.workLines.size * 2)
    var grandTotal = 0L
    for (l in s.workLines) {
        if (l.quantity < 1 || l.quantity > INT_MAX) {
            reject(SnapshotInvalidReason.INVALID_QUANTITY, "workLines: (${l.entryId}, ${l.procedureId})")
        }
        if (l.appliedAmountMinor < 0) {
            reject(SnapshotInvalidReason.NEGATIVE_AMOUNT, "workLines: (${l.entryId}, ${l.procedureId})")
        }
        if (l.entryId !in entryIds) {
            reject(SnapshotInvalidReason.DANGLING_REFERENCE, "workLines: entry ${l.entryId} not found")
        }
        if (l.procedureId !in procedureIds) {
            reject(SnapshotInvalidReason.DANGLING_REFERENCE, "workLines: procedure ${l.procedureId} not found")
        }
        if (!linePairs.add(l.entryId to l.procedureId)) {
            reject(SnapshotInvalidReason.DUPLICATE_PAIR, "workLines: (${l.entryId}, ${l.procedureId})")
        }
        // Line total and grand total must fit in Long, matching report calculations.
        if (l.appliedAmountMinor > Long.MAX_VALUE / l.quantity) {
            reject(SnapshotInvalidReason.AMOUNT_OVERFLOW, "workLines: (${l.entryId}, ${l.procedureId})")
        }
        val lineTotal = l.appliedAmountMinor * l.quantity
        if (lineTotal > Long.MAX_VALUE - grandTotal) {
            reject(SnapshotInvalidReason.AMOUNT_OVERFLOW, "workLines: total amount")
        }
        grandTotal += lineTotal
    }

    // From version 2 an entry must carry lines or a scheduled count, as the app never stores an empty one.
    // Version 1 files are checked as before, so an existing backup never becomes unrestorable.
    if (s.schemaVersion >= 2) {
        val entriesWithLines = s.workLines.mapTo(HashSet(s.workLines.size * 2)) { it.entryId }
        for (e in s.workEntries) {
            if (e.scheduledCount == null && e.id !in entriesWithLines) {
                reject(SnapshotInvalidReason.EMPTY_ENTRY, "workEntries: entry ${e.id}")
            }
        }
    }
}

private fun limit(table: String, size: Int, max: Int) {
    if (size > max) reject(SnapshotInvalidReason.TOO_MANY_ROWS, "$table: $size > $max")
}

private fun positiveId(table: String, id: Long) {
    if (id < 1) reject(SnapshotInvalidReason.INVALID_ID, "$table: id $id")
}

private fun checkName(table: String, id: Long, name: String) {
    if (name.isBlank()) reject(SnapshotInvalidReason.BLANK_NAME, "$table: id $id")
    if (name.length > SnapshotLimits.MAX_NAME_LENGTH) reject(SnapshotInvalidReason.NAME_TOO_LONG, "$table: id $id")
}

private val UTC_TIMESTAMP = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z""")

private fun isUtcTimestamp(text: String): Boolean {
    if (!UTC_TIMESTAMP.matches(text)) return false
    return try {
        Instant.parse(text)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}
