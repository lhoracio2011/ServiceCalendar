package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import app.servicecalendar.db.getLongOrNull
import app.servicecalendar.db.getTextOrNull
import app.servicecalendar.db.query
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.completedCount
import app.servicecalendar.domain.lineTotal
import app.servicecalendar.domain.percentDone
import app.servicecalendar.domain.sumAmounts
import app.servicecalendar.domain.toStorageText
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

data class PeriodReport(
    val from: LocalDate,
    val to: LocalDate,
    val places: List<PlaceReportRow>,
    val total: MinorAmount,
    /** ΣA over the period; null when no entry in the period has A (shown as "—"). */
    val scheduled: Long? = null,
    /** R of the A/R/% line: quantities of entries that have A; if no entry has A, the sum of all quantities. */
    val completed: Long = 0,
    /** R / ΣA × 100 over entries with A only; null when there is no A or ΣA is 0. */
    val percent: Double? = null,
)

/** One procedure's quantity and amount over the period at one place. */
data class ProcedureReportRow(
    val procedureId: Long,
    val name: String,
    val archived: Boolean,
    val quantity: Long,
    val total: MinorAmount,
)

data class PlaceReportRow(
    val place: Place,
    val total: MinorAmount,
    /** Days with an entry, including days that have only a scheduled count. */
    val entryDays: Int,
    val procedures: List<ProcedureReportRow> = emptyList(),
    /** Σ of entered A over days; null when no day has A. */
    val scheduled: Long? = null,
    /** Σ quantities of days that have A; if no day has A, the sum of all quantities. */
    val completed: Long = 0,
    val percent: Double? = null,
)

/** Totals use each line's stored appliedAmountMinor times quantity, not current rates, so rate changes never alter old reports. */
class ReportRepository(private val store: DataStore) {
    suspend fun report(from: LocalDate, to: LocalDate): PeriodReport {
        validatePeriod(from, to)
        val fromText = from.toStorageText()
        val toText = to.toStorageText()
        return store.read { db -> db.readReport(from, to, fromText, toText) }
    }

    fun reportFlow(from: LocalDate, to: LocalDate): Flow<PeriodReport> {
        validatePeriod(from, to)
        val fromText = from.toStorageText()
        val toText = to.toStorageText()
        return store.revision
            .map { store.read { db -> db.readReport(from, to, fromText, toText) } }
            .distinctUntilChanged()
    }

    private fun validatePeriod(from: LocalDate, to: LocalDate) {
        if (to < from) throw ValidationError("period", "end date is before the start date")
    }
}

private class ReportLine(
    val place: Place,
    val entryId: Long,
    val scheduled: Int?,
    val procedureId: Long?,
    val procedureName: String?,
    val procedureArchived: Boolean,
    val quantity: Int,
    val total: MinorAmount,
)

private fun SQLiteConnection.readReport(
    from: LocalDate,
    to: LocalDate,
    fromText: String,
    toText: String,
): PeriodReport {
    // LEFT JOIN keeps entries that have only a scheduled count. It repeats the entry once per line, so A is read once per entry ID.
    val lines = query(
        """
        SELECT p.id, p.name, p.address, p.color, p.archived, e.id, e.scheduledCount,
               l.procedureId, pr.name, pr.archived, l.quantity, l.appliedAmountMinor
        FROM work_entry e
        JOIN place p ON p.id = e.placeId
        LEFT JOIN work_line l ON l.entryId = e.id
        LEFT JOIN procedure pr ON pr.id = l.procedureId
        WHERE e.localDate BETWEEN ? AND ?
          AND (l.entryId IS NOT NULL OR e.scheduledCount IS NOT NULL)
        ORDER BY p.id, e.id, l.procedureId
        """.trimIndent(),
        binder = {
            bindText(1, fromText)
            bindText(2, toText)
        },
        mapper = {
            val hasLine = !it.isNull(7)
            val quantity = if (hasLine) it.getLong(10).toInt() else 0
            ReportLine(
                place = Place(
                    id = it.getLong(0),
                    name = it.getText(1),
                    address = it.getTextOrNull(2),
                    color = it.getLong(3),
                    archived = it.getLong(4) != 0L,
                ),
                entryId = it.getLong(5),
                scheduled = it.getLongOrNull(6)?.toInt(),
                procedureId = if (hasLine) it.getLong(7) else null,
                procedureName = if (hasLine) it.getText(8) else null,
                procedureArchived = hasLine && it.getLong(9) != 0L,
                quantity = quantity,
                total = if (hasLine) lineTotal(MinorAmount(it.getLong(11)), quantity) else MinorAmount(0),
            )
        },
    )
    val rows = lines.groupBy { it.place.id }.values.map { placeLines ->
        val procedures = placeLines.filter { it.procedureId != null }
            .groupBy { it.procedureId }
            .values.map { group ->
                ProcedureReportRow(
                    procedureId = checkNotNull(group.first().procedureId),
                    name = checkNotNull(group.first().procedureName),
                    archived = group.first().procedureArchived,
                    quantity = completedCount(group.map { it.quantity }),
                    total = sumAmounts(group.map { it.total }),
                )
            }.sortedWith(compareBy({ it.name.lowercase() }, { it.procedureId }))
        val withA = placeLines.filter { it.scheduled != null }
        val scheduled = if (withA.isEmpty()) null else withA.distinctBy { it.entryId }.sumOf { checkNotNull(it.scheduled).toLong() }
        // R is limited to days with A so that it stays consistent with the percent; without any A it shows everything done.
        val completed = completedCount((if (scheduled == null) placeLines else withA).map { it.quantity })
        PlaceReportRow(
            place = placeLines.first().place,
            total = sumAmounts(placeLines.map { it.total }),
            // One entry exists per (date, place), so distinct entry IDs are distinct days.
            entryDays = placeLines.mapTo(HashSet()) { it.entryId }.size,
            procedures = procedures,
            scheduled = scheduled,
            completed = completed,
            percent = percentOf(completed, scheduled),
        )
    }.sortedWith(compareBy({ it.place.name.lowercase() }, { it.place.id }))
    val rowsWithA = rows.filter { it.scheduled != null }
    val scheduled = if (rowsWithA.isEmpty()) null else rowsWithA.sumOf { checkNotNull(it.scheduled) }
    val completed = (if (scheduled == null) rows else rowsWithA).sumOf { it.completed }
    return PeriodReport(
        from = from,
        to = to,
        places = rows,
        total = sumAmounts(rows.map { it.total }),
        scheduled = scheduled,
        completed = completed,
        percent = percentOf(completed, scheduled),
    )
}

/** Sums of A can exceed Int, so they are Long here; percentDone takes Int, and a sum above Int.MAX is clamped (the ratio is then ~0). */
private fun percentOf(completed: Long, scheduled: Long?): Double? =
    if (scheduled == null || scheduled <= 0L) null else percentDone(completed, minOf(scheduled, Int.MAX_VALUE.toLong()).toInt())
