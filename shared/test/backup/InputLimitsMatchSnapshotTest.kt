package app.servicecalendar.backup

import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.validateCatalogName
import app.servicecalendar.domain.validatePlaceDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Anything input validation accepts also passes the snapshot validator; one more character is rejected by both. */
class InputLimitsMatchSnapshotTest {
    private val base = sampleSnapshot()

    @Test
    fun `snapshot limits are the domain limits`() {
        assertEquals(CatalogLimits.MAX_NAME_LENGTH, SnapshotLimits.MAX_NAME_LENGTH)
        assertEquals(CatalogLimits.MAX_ADDRESS_LENGTH, SnapshotLimits.MAX_ADDRESS_LENGTH)
        assertEquals(CatalogLimits.MAX_PROCEDURES, SnapshotLimits.MAX_PROCEDURES)
        assertEquals(CatalogLimits.MAX_PLACES, SnapshotLimits.MAX_PLACES)
        assertEquals(CatalogLimits.MAX_PLACE_RATES, SnapshotLimits.MAX_PLACE_RATES)
        assertEquals(CatalogLimits.MAX_WORK_ENTRIES, SnapshotLimits.MAX_WORK_ENTRIES)
        assertEquals(CatalogLimits.MAX_WORK_LINES, SnapshotLimits.MAX_WORK_LINES)
    }

    @Test
    fun `max length input passes both validators`() {
        val draft = validatePlaceDraft(
            PlaceDraft(
                null,
                "а".repeat(CatalogLimits.MAX_NAME_LENGTH),
                "x".repeat(CatalogLimits.MAX_ADDRESS_LENGTH),
                0xFF2E7D32L,
                emptyMap(),
            ),
        )
        val procedureName = validateCatalogName("б".repeat(CatalogLimits.MAX_NAME_LENGTH))
        val snapshot = base.copy(
            procedures = listOf(base.procedures[0].copy(name = procedureName), base.procedures[1]),
            places = listOf(base.places[0].copy(name = draft.name, address = draft.address), base.places[1]),
        )
        assertEquals(SnapshotValidationResult.Valid(snapshot), validateSnapshot(snapshot))
    }

    @Test
    fun `one char over the limit is rejected by input validation and by the snapshot validator`() {
        val name = "а".repeat(CatalogLimits.MAX_NAME_LENGTH + 1)
        val address = "x".repeat(CatalogLimits.MAX_ADDRESS_LENGTH + 1)
        assertFailsWith<ValidationError> { validateCatalogName(name) }
        assertFailsWith<ValidationError> { validatePlaceDraft(PlaceDraft(null, "М", address, 0xFF2E7D32L, emptyMap())) }

        val snapshot = base.copy(
            places = listOf(base.places[0].copy(name = name, address = address), base.places[1]),
        )
        val result = validateSnapshot(snapshot)
        assertTrue(result is SnapshotValidationResult.Invalid)
        assertEquals(SnapshotInvalidReason.NAME_TOO_LONG, result.reason)
    }
}
