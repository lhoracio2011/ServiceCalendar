package app.servicecalendar.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Snapshot validation before a restore. */
class SnapshotValidatorTest {
    private val base = sampleSnapshot()

    private fun assertInvalid(
        expected: SnapshotInvalidReason,
        snapshot: BackupSnapshot,
    ) {
        val result = validateSnapshot(snapshot)
        assertTrue(result is SnapshotValidationResult.Invalid, "Expected $expected but the snapshot was accepted")
        assertEquals(expected, result.reason, "detail: ${result.detail}")
        assertTrue(result.detail.isNotBlank())
        assertTrue(result.userMessage().isNotBlank())
    }

    private fun assertValid(snapshot: BackupSnapshot) {
        val result = validateSnapshot(snapshot)
        assertEquals(SnapshotValidationResult.Valid(snapshot), result)
    }

    @Test
    fun `the sample snapshot is valid`() = assertValid(base)

    @Test
    fun `the set with archived rows is valid`() = assertValid(snapshotWithArchivedRows())

    @Test
    fun `an empty dataset is valid`() = assertValid(
        base.copy(
            procedures = emptyList(), places = emptyList(), placeRates = emptyList(),
            workEntries = emptyList(), workLines = emptyList(),
        ),
    )

    @Test
    fun `boundary values are valid`() = assertValid(
        base.copy(
            places = base.places.map { it.copy(color = 0L) }.let {
                listOf(it[0], it[1].copy(color = 4_294_967_295L))
            },
            placeRates = base.placeRates.map { it.copy(amountMinor = 0) },
            workLines = base.workLines.map { it.copy(quantity = Int.MAX_VALUE.toLong(), appliedAmountMinor = 0) },
        ),
    )

    @Test
    fun `a newer schema version is rejected`() {
        assertInvalid(SnapshotInvalidReason.NEWER_VERSION, base.copy(schemaVersion = BackupSnapshot.CURRENT_VERSION + 1))
        assertInvalid(SnapshotInvalidReason.NEWER_VERSION, base.copy(schemaVersion = Int.MAX_VALUE))
    }

    @Test
    fun `a zero or negative schema version is rejected`() {
        assertInvalid(SnapshotInvalidReason.UNSUPPORTED_VERSION, base.copy(schemaVersion = 0))
        assertInvalid(SnapshotInvalidReason.UNSUPPORTED_VERSION, base.copy(schemaVersion = -1))
    }

    @Test
    fun `an invalid createdAt is rejected`() {
        for (bad in listOf("", "yesterday", "2026-11-09", "2026-11-09T21:30:00", "2026-11-09T21:30:00+02:00", "2026-13-09T21:30:00Z")) {
            assertInvalid(SnapshotInvalidReason.INVALID_CREATED_AT, base.copy(createdAt = bad))
        }
    }

    @Test
    fun `a createdAt with a year outside 0000-9999 is rejected`() {
        for (bad in listOf("+10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "-2026-11-09T21:30:00Z", "+2026-11-09T21:30:00Z")) {
            assertInvalid(SnapshotInvalidReason.INVALID_CREATED_AT, base.copy(createdAt = bad))
        }
    }

    @Test
    fun `a createdAt with fractional seconds in UTC is accepted`() =
        assertValid(base.copy(createdAt = "2026-11-09T21:30:00.123Z"))

    @Test
    fun `an empty or invalid currency code is rejected`() {
        for (bad in listOf("", " ", "BR", "BRLL", "brl", "Brl", "B1L", "ЮАН", "BR\n")) {
            assertInvalid(SnapshotInvalidReason.INVALID_CURRENCY, base.copy(currencyCode = bad))
        }
    }

    @Test
    fun `a blank procedure name is rejected`() {
        for (bad in listOf("", "   ", "\t\n")) {
            assertInvalid(
                SnapshotInvalidReason.BLANK_NAME,
                base.copy(procedures = listOf(base.procedures[0].copy(name = bad), base.procedures[1])),
            )
        }
    }

    @Test
    fun `a blank place name is rejected`() {
        for (bad in listOf("", "   ", "\t")) {
            assertInvalid(
                SnapshotInvalidReason.BLANK_NAME,
                base.copy(places = listOf(base.places[0].copy(name = bad), base.places[1])),
            )
        }
    }

    @Test
    fun `an overlong name is rejected`() {
        val tooLong = "а".repeat(SnapshotLimits.MAX_NAME_LENGTH + 1)
        assertInvalid(
            SnapshotInvalidReason.NAME_TOO_LONG,
            base.copy(procedures = listOf(base.procedures[0].copy(name = tooLong), base.procedures[1])),
        )
        assertInvalid(
            SnapshotInvalidReason.NAME_TOO_LONG,
            base.copy(places = listOf(base.places[0].copy(name = tooLong), base.places[1])),
        )
        assertValid(
            base.copy(
                procedures = listOf(
                    base.procedures[0].copy(name = "а".repeat(SnapshotLimits.MAX_NAME_LENGTH)),
                    base.procedures[1],
                ),
            ),
        )
    }

    @Test
    fun `an overlong address is rejected`() {
        assertInvalid(
            SnapshotInvalidReason.NAME_TOO_LONG,
            base.copy(
                places = listOf(
                    base.places[0].copy(address = "x".repeat(SnapshotLimits.MAX_ADDRESS_LENGTH + 1)),
                    base.places[1],
                ),
            ),
        )
        assertValid(
            base.copy(
                places = listOf(
                    base.places[0].copy(address = "x".repeat(SnapshotLimits.MAX_ADDRESS_LENGTH)),
                    base.places[1],
                ),
            ),
        )
    }

    @Test
    fun `duplicate procedure ids are rejected`() = assertInvalid(
        SnapshotInvalidReason.DUPLICATE_ID,
        base.copy(procedures = listOf(base.procedures[0], base.procedures[1].copy(id = 1))),
    )

    @Test
    fun `duplicate place ids are rejected`() = assertInvalid(
        SnapshotInvalidReason.DUPLICATE_ID,
        base.copy(places = listOf(base.places[0], base.places[1].copy(id = 1))),
    )

    @Test
    fun `duplicate entry ids are rejected`() = assertInvalid(
        SnapshotInvalidReason.DUPLICATE_ID,
        base.copy(workEntries = base.workEntries.mapIndexed { i, e -> if (i == 1) e.copy(id = 1) else e }),
    )

    @Test
    fun `a non-positive id is rejected`() {
        for (bad in listOf(0L, -1L)) {
            assertInvalid(
                SnapshotInvalidReason.INVALID_ID,
                base.copy(procedures = listOf(base.procedures[0].copy(id = bad), base.procedures[1])),
            )
        }
    }

    @Test
    fun `a rate pointing to a missing place is rejected`() = assertInvalid(
        SnapshotInvalidReason.DANGLING_REFERENCE,
        base.copy(placeRates = base.placeRates + PlaceRateDto(placeId = 99, procedureId = 1, amountMinor = 1)),
    )

    @Test
    fun `a rate pointing to a missing procedure is rejected`() = assertInvalid(
        SnapshotInvalidReason.DANGLING_REFERENCE,
        base.copy(placeRates = base.placeRates + PlaceRateDto(placeId = 2, procedureId = 99, amountMinor = 1)),
    )

    @Test
    fun `an entry pointing to a missing place is rejected`() = assertInvalid(
        SnapshotInvalidReason.DANGLING_REFERENCE,
        base.copy(workEntries = base.workEntries + WorkEntryDto(id = 9, localDate = "2026-11-05", placeId = 99)),
    )

    @Test
    fun `a line pointing to a missing entry is rejected`() = assertInvalid(
        SnapshotInvalidReason.DANGLING_REFERENCE,
        base.copy(workLines = base.workLines + WorkLineDto(entryId = 99, procedureId = 1, quantity = 1, appliedAmountMinor = 1)),
    )

    @Test
    fun `a line pointing to a missing procedure is rejected`() = assertInvalid(
        SnapshotInvalidReason.DANGLING_REFERENCE,
        base.copy(workLines = base.workLines + WorkLineDto(entryId = 4, procedureId = 99, quantity = 1, appliedAmountMinor = 1)),
    )

    @Test
    fun `a duplicate rate pair is rejected`() = assertInvalid(
        SnapshotInvalidReason.DUPLICATE_PAIR,
        base.copy(placeRates = base.placeRates + PlaceRateDto(placeId = 1, procedureId = 1, amountMinor = 5)),
    )

    @Test
    fun `a duplicate date and place pair is rejected`() = assertInvalid(
        SnapshotInvalidReason.DUPLICATE_PAIR,
        base.copy(workEntries = base.workEntries + WorkEntryDto(id = 9, localDate = "2026-11-01", placeId = 1)),
    )

    @Test
    fun `a duplicate line pair is rejected`() = assertInvalid(
        SnapshotInvalidReason.DUPLICATE_PAIR,
        base.copy(workLines = base.workLines + WorkLineDto(entryId = 1, procedureId = 1, quantity = 1, appliedAmountMinor = 1)),
    )

    @Test
    fun `a negative rate is rejected`() = assertInvalid(
        SnapshotInvalidReason.NEGATIVE_AMOUNT,
        base.copy(placeRates = listOf(base.placeRates[0].copy(amountMinor = -1)) + base.placeRates.drop(1)),
    )

    @Test
    fun `a negative applied amount is rejected`() = assertInvalid(
        SnapshotInvalidReason.NEGATIVE_AMOUNT,
        base.copy(workLines = listOf(base.workLines[0].copy(appliedAmountMinor = -1)) + base.workLines.drop(1)),
    )

    @Test
    fun `zero and negative quantities are rejected`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertInvalid(
                SnapshotInvalidReason.INVALID_QUANTITY,
                base.copy(workLines = listOf(base.workLines[0].copy(quantity = bad)) + base.workLines.drop(1)),
            )
        }
    }

    @Test
    fun `a quantity above Int MAX_VALUE is rejected`() {
        for (bad in listOf(Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertInvalid(
                SnapshotInvalidReason.INVALID_QUANTITY,
                base.copy(workLines = listOf(base.workLines[0].copy(quantity = bad)) + base.workLines.drop(1)),
            )
        }
    }

    @Test
    fun `a line total that overflows Long is rejected`() = assertInvalid(
        SnapshotInvalidReason.AMOUNT_OVERFLOW,
        base.copy(
            workLines = listOf(base.workLines[0].copy(quantity = 2, appliedAmountMinor = Long.MAX_VALUE / 2 + 1)) +
                base.workLines.drop(1),
        ),
    )

    @Test
    fun `a sum of lines that overflows Long is rejected`() = assertInvalid(
        SnapshotInvalidReason.AMOUNT_OVERFLOW,
        base.copy(
            workLines = base.workLines.mapIndexed { i, l ->
                if (i < 2) l.copy(quantity = 1, appliedAmountMinor = Long.MAX_VALUE / 2 + 1) else l
            },
        ),
    )

    @Test
    fun `an entry with only a scheduled count is valid`() = assertValid(snapshotWithScheduledOnlyEntry())

    @Test
    fun `scheduled count boundaries are valid`() {
        for (value in listOf(0L, Int.MAX_VALUE.toLong())) {
            assertValid(base.copy(workEntries = base.workEntries.map { it.copy(scheduledCount = value) }))
        }
    }

    @Test
    fun `a scheduled count out of range is rejected`() {
        for (bad in listOf(-1L, Long.MIN_VALUE, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertInvalid(
                SnapshotInvalidReason.INVALID_SCHEDULED_COUNT,
                base.copy(workEntries = listOf(base.workEntries[0].copy(scheduledCount = bad)) + base.workEntries.drop(1)),
            )
        }
    }

    @Test
    fun `an entry without lines and without a scheduled count is rejected`() = assertInvalid(
        SnapshotInvalidReason.EMPTY_ENTRY,
        base.copy(workEntries = base.workEntries + WorkEntryDto(id = 9, localDate = "2026-11-05", placeId = 1)),
    )

    @Test
    fun `a version 1 snapshot is valid and keeps accepting an entry without lines`() {
        val v1 = base.copy(schemaVersion = 1, workEntries = base.workEntries.map { it.copy(scheduledCount = null) })
        assertValid(v1)
        // Version 1 never required lines per entry; old copies stay restorable.
        assertValid(v1.copy(workEntries = v1.workEntries + WorkEntryDto(id = 9, localDate = "2026-11-05", placeId = 1)))
    }

    @Test
    fun `parseAndValidate accepts a version 1 document without scheduled counts`() {
        val root = Json.parseToJsonElement(validJson()).jsonObject
        val v1 = JsonObject(
            root + ("schemaVersion" to JsonPrimitive(1)) + (
                "workEntries" to JsonArray(
                    root["workEntries"]!!.jsonArray.map { e -> JsonObject(e.jsonObject - "scheduledCount") },
                )
                ),
        )
        val result = parseAndValidate(v1.toString())
        assertTrue(result is SnapshotValidationResult.Valid, "$result")
        assertEquals(1, result.snapshot.schemaVersion)
        assertTrue(result.snapshot.workEntries.all { it.scheduledCount == null })
    }

    @Test
    fun `parseAndValidate reports a wrongly typed scheduled count`() {
        val text = validJson()
        assertJsonInvalid(SnapshotInvalidReason.WRONG_FIELD_TYPE, text.replaceFirst("\"scheduledCount\":8", "\"scheduledCount\":8.5"))
        assertJsonInvalid(SnapshotInvalidReason.WRONG_FIELD_TYPE, text.replaceFirst("\"scheduledCount\":8", "\"scheduledCount\":\"8\""))
    }

    @Test
    fun `parseAndValidate reports a scheduled count above Int MAX_VALUE`() {
        val text = validJson().replaceFirst("\"scheduledCount\":8", "\"scheduledCount\":2147483648")
        assertJsonInvalid(SnapshotInvalidReason.INVALID_SCHEDULED_COUNT, text)
    }

    @Test
    fun `invalid or non-calendar dates are rejected`() {
        val bad = listOf(
            "", "2026-02-30", "2026-13-01", "2026-00-10", "2026-11-31", "2025-02-29",
            "2026-1-1", "26-11-01", "2026/11/01", "2026-11-01T00:00", " 2026-11-01", "2026-11-0a", "2026-11-01\n",
        )
        for (date in bad) {
            assertInvalid(
                SnapshotInvalidReason.INVALID_DATE,
                base.copy(workEntries = listOf(base.workEntries[0].copy(localDate = date)) + base.workEntries.drop(1)),
            )
        }
    }

    @Test
    fun `a leap day is a valid date`() = assertValid(
        base.copy(workEntries = listOf(base.workEntries[0].copy(localDate = "2028-02-29")) + base.workEntries.drop(1)),
    )

    @Test
    fun `a color outside ARGB range is rejected`() {
        for (bad in listOf(-1L, 4_294_967_296L, Long.MAX_VALUE)) {
            assertInvalid(
                SnapshotInvalidReason.INVALID_COLOR,
                base.copy(places = listOf(base.places[0].copy(color = bad), base.places[1])),
            )
        }
    }

    @Test
    fun `too many procedures are rejected`() = assertInvalid(
        SnapshotInvalidReason.TOO_MANY_ROWS,
        base.copy(procedures = List(SnapshotLimits.MAX_PROCEDURES + 1) { base.procedures[0] }),
    )

    @Test
    fun `too many places are rejected`() = assertInvalid(
        SnapshotInvalidReason.TOO_MANY_ROWS,
        base.copy(places = List(SnapshotLimits.MAX_PLACES + 1) { base.places[0] }),
    )

    @Test
    fun `too many rates are rejected`() = assertInvalid(
        SnapshotInvalidReason.TOO_MANY_ROWS,
        base.copy(placeRates = List(SnapshotLimits.MAX_PLACE_RATES + 1) { base.placeRates[0] }),
    )

    @Test
    fun `too many entries are rejected`() = assertInvalid(
        SnapshotInvalidReason.TOO_MANY_ROWS,
        base.copy(workEntries = List(SnapshotLimits.MAX_WORK_ENTRIES + 1) { base.workEntries[0] }),
    )

    @Test
    fun `too many lines are rejected`() = assertInvalid(
        SnapshotInvalidReason.TOO_MANY_ROWS,
        base.copy(workLines = List(SnapshotLimits.MAX_WORK_LINES + 1) { base.workLines[0] }),
    )

    private fun validJson(): String = SnapshotCodec.encode(base)

    private fun assertJsonInvalid(expected: SnapshotInvalidReason, json: String, maxChars: Int = SnapshotLimits.MAX_JSON_CHARS) {
        val result = parseAndValidate(json, maxChars)
        assertTrue(result is SnapshotValidationResult.Invalid, "Expected $expected")
        assertEquals(expected, result.reason, "detail: ${result.detail}")
        assertTrue(result.userMessage().isNotBlank())
    }

    @Test
    fun `parseAndValidate accepts a valid document`() {
        assertEquals(SnapshotValidationResult.Valid(base), parseAndValidate(validJson()))
    }

    @Test
    fun `parseAndValidate reports malformed json without a raw exception`() {
        for (bad in listOf("", "   ", "{ not json", "{\"schemaVersion\":", "<html></html>", "\u0000\u0001")) {
            assertJsonInvalid(SnapshotInvalidReason.MALFORMED_JSON, bad)
        }
    }

    @Test
    fun `parseAndValidate rejects json that is not an object`() {
        for (bad in listOf("[]", "null", "42", "\"text\"", "[{\"schemaVersion\":1}]")) {
            assertJsonInvalid(SnapshotInvalidReason.MALFORMED_JSON, bad)
        }
    }

    @Test
    fun `parseAndValidate reports a truncated document as malformed`() {
        val text = validJson()
        assertJsonInvalid(SnapshotInvalidReason.MALFORMED_JSON, text.take(text.length / 2))
    }

    @Test
    fun `parseAndValidate reports a missing root field`() {
        val root = Json.parseToJsonElement(validJson()).jsonObject
        for (key in root.keys) {
            assertJsonInvalid(SnapshotInvalidReason.MISSING_FIELD, JsonObject(root - key).toString())
        }
    }

    @Test
    fun `parseAndValidate reports a missing row field`() {
        val root = Json.parseToJsonElement(validJson()).jsonObject
        val line = root["workLines"]!!.jsonArray[0].jsonObject
        val broken = JsonObject(
            root + ("workLines" to JsonArray(listOf(JsonObject(line - "quantity")) + root["workLines"]!!.jsonArray.drop(1))),
        )
        assertJsonInvalid(SnapshotInvalidReason.MISSING_FIELD, broken.toString())
    }

    @Test
    fun `parseAndValidate reports a wrongly typed field`() {
        val text = validJson()
        assertJsonInvalid(SnapshotInvalidReason.WRONG_FIELD_TYPE, text.replaceFirst("\"amountMinor\":1250", "\"amountMinor\":12.5"))
        assertJsonInvalid(SnapshotInvalidReason.WRONG_FIELD_TYPE, text.replaceFirst("\"amountMinor\":1250", "\"amountMinor\":\"1250\""))
        assertJsonInvalid(SnapshotInvalidReason.WRONG_FIELD_TYPE, text.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":\"2\""))
        assertJsonInvalid(SnapshotInvalidReason.WRONG_FIELD_TYPE, text.replaceFirst("\"procedures\":[", "\"procedures\":{},\"x\":["))
    }

    @Test
    fun `parseAndValidate reports a newer version even when the structure differs`() {
        assertJsonInvalid(SnapshotInvalidReason.NEWER_VERSION, "{\"schemaVersion\":3,\"somethingNew\":true}")
        val root = Json.parseToJsonElement(validJson()).jsonObject
        assertJsonInvalid(SnapshotInvalidReason.NEWER_VERSION, JsonObject(root + ("schemaVersion" to JsonPrimitive(7))).toString())
    }

    @Test
    fun `parseAndValidate reports a missing schema version`() {
        assertJsonInvalid(SnapshotInvalidReason.MISSING_FIELD, "{}")
    }

    @Test
    fun `parseAndValidate reports semantic errors of a well-formed document`() {
        val text = validJson().replaceFirst("\"localDate\":\"2026-11-01\"", "\"localDate\":\"2026-02-30\"")
        assertJsonInvalid(SnapshotInvalidReason.INVALID_DATE, text)
    }

    @Test
    fun `parseAndValidate rejects a document over the size limit`() {
        val text = validJson()
        assertJsonInvalid(SnapshotInvalidReason.TOO_LARGE, text, maxChars = text.length - 1)
        assertEquals(SnapshotValidationResult.Valid(base), parseAndValidate(text, maxChars = text.length))
    }

    @Test
    fun `every reason has a distinct non-empty English message`() {
        val messages = SnapshotInvalidReason.entries.map { it.userMessage() }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(messages.size, messages.toSet().size, "Reason messages must differ")
        val latin = Regex("[A-Za-z]")
        val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")
        for (message in messages) {
            assertTrue(latin.containsMatchIn(message) && !cyrillic.containsMatchIn(message), message)
        }
    }
}
