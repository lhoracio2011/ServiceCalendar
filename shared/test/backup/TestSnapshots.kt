package app.servicecalendar.backup

/** Colors of place A (green) and place B (blue). */
const val TEST_COLOR_A: Long = 0xFF2E7D32L
const val TEST_COLOR_B: Long = 0xFF1565C0L

const val TEST_CREATED_AT: String = "2026-11-09T21:30:00Z"

/**
 * In-memory snapshot used across backup tests:
 *
 * - procedures: 1 "Огляд", 2 "Перев'язка";
 * - places: 1 "A" (rates Огляд 12.50, Перев'язка 7.75), 2 "B" (Огляд 20.00);
 * - 01.11 A: Огляд x3 and Перев'язка x2 (53.00 total), 8 scheduled; 01.11 B: Огляд x2 (40.00);
 * - 02.11 A: Огляд x1 (12.50); 03.11 B: Огляд x4 (80.00).
 */
fun sampleSnapshot(
    createdAt: String = TEST_CREATED_AT,
    currencyCode: String = "BRL",
): BackupSnapshot = BackupSnapshot(
    schemaVersion = BackupSnapshot.CURRENT_VERSION,
    createdAt = createdAt,
    currencyCode = currencyCode,
    procedures = listOf(
        ProcedureDto(id = 1, name = "Огляд", archived = false),
        ProcedureDto(id = 2, name = "Перев'язка", archived = false),
    ),
    places = listOf(
        PlaceDto(id = 1, name = "A", address = "вул. Квіткова, 1", color = TEST_COLOR_A, archived = false),
        PlaceDto(id = 2, name = "B", address = null, color = TEST_COLOR_B, archived = false),
    ),
    placeRates = listOf(
        PlaceRateDto(placeId = 1, procedureId = 1, amountMinor = 1250),
        PlaceRateDto(placeId = 1, procedureId = 2, amountMinor = 775),
        PlaceRateDto(placeId = 2, procedureId = 1, amountMinor = 2000),
    ),
    workEntries = listOf(
        WorkEntryDto(id = 1, localDate = "2026-11-01", placeId = 1, scheduledCount = 8),
        WorkEntryDto(id = 2, localDate = "2026-11-01", placeId = 2),
        WorkEntryDto(id = 3, localDate = "2026-11-02", placeId = 1),
        WorkEntryDto(id = 4, localDate = "2026-11-03", placeId = 2),
    ),
    workLines = listOf(
        WorkLineDto(entryId = 1, procedureId = 1, quantity = 3, appliedAmountMinor = 1250),
        WorkLineDto(entryId = 1, procedureId = 2, quantity = 2, appliedAmountMinor = 775),
        WorkLineDto(entryId = 2, procedureId = 1, quantity = 2, appliedAmountMinor = 2000),
        WorkLineDto(entryId = 3, procedureId = 1, quantity = 1, appliedAmountMinor = 1250),
        WorkLineDto(entryId = 4, procedureId = 1, quantity = 4, appliedAmountMinor = 2000),
    ),
)

/** The same snapshot plus an archived procedure and an archived place that have history and a rate. */
fun snapshotWithArchivedRows(): BackupSnapshot {
    val base = sampleSnapshot()
    return base.copy(
        procedures = base.procedures + ProcedureDto(id = 3, name = "Масаж", archived = true),
        places = base.places + PlaceDto(id = 3, name = "Старе місце", address = null, color = 0L, archived = true),
        placeRates = base.placeRates + PlaceRateDto(placeId = 3, procedureId = 3, amountMinor = 0),
        workEntries = base.workEntries + WorkEntryDto(id = 5, localDate = "2026-10-31", placeId = 3),
        workLines = base.workLines + WorkLineDto(entryId = 5, procedureId = 3, quantity = 1, appliedAmountMinor = 0),
    )
}

/** The sample snapshot plus entry 5 on 04.11 at A with 5 scheduled and no lines yet. */
fun snapshotWithScheduledOnlyEntry(): BackupSnapshot {
    val base = sampleSnapshot()
    return base.copy(workEntries = base.workEntries + WorkEntryDto(id = 5, localDate = "2026-11-04", placeId = 1, scheduledCount = 5))
}
