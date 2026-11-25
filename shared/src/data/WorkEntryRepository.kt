package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import app.servicecalendar.db.bindLongOrNull
import app.servicecalendar.db.execute
import app.servicecalendar.db.getLongOrNull
import app.servicecalendar.db.getTextOrNull
import app.servicecalendar.db.query
import app.servicecalendar.db.readDataGeneration
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.dayTotal
import app.servicecalendar.domain.lineTotal
import app.servicecalendar.domain.parseStorageDate
import app.servicecalendar.domain.sumAmounts
import app.servicecalendar.domain.toStorageText
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

class WorkEntryRepository(private val store: DataStore) {
    suspend fun loadEditor(date: LocalDate, placeId: Long): DayEntryEditorData {
        val dateText = date.toStorageText()
        return store.read { db -> db.readEditor(date, dateText, placeId) }
    }

    /**
     * Quantities hold the full editor state; a zero or missing procedure deletes its line.
     * [scheduled] defaults to [ScheduledChange.Keep], so a save from the editor never touches the stored scheduled count (A).
     * The entry is deleted only when it is left with no lines and no scheduled count; otherwise it stays, and the result
     * carries its ID even when only the scheduled count remains (see [DayEntrySaveResult]).
     * Existing lines keep their historical appliedAmountMinor; new lines take the place's current rate.
     * openedGeneration is checked in the same write block, so a restore cannot interleave.
     */
    suspend fun save(
        date: LocalDate,
        placeId: Long,
        quantities: Map<Long, Int>,
        openedGeneration: Long,
        scheduled: ScheduledChange = ScheduledChange.Keep,
    ): DayEntrySaveResult {
        val dateText = date.toStorageText()
        for ((procedureId, quantity) in quantities) {
            if (quantity < 0) throw ValidationError("quantity.$procedureId", "quantity cannot be negative")
        }
        if (scheduled is ScheduledChange.Set) validateScheduled(scheduled.value)
        val wanted = quantities.entries.filter { it.value > 0 }.sortedBy { it.key }.map { it.key to it.value }

        return store.write { db ->
            if (db.readDataGeneration() != openedGeneration) throw StaleGeneration()
            val place = db.readPlace(placeId) ?: throw ValidationError("placeId", "place not found")
            val entry = db.findEntry(dateText, placeId)
            val entryId = entry?.id
            val saved = if (entryId == null) emptyMap() else db.readSavedLines(entryId).associateBy { it.procedureId }
            val rates = db.readActiveRates(placeId).associateBy { it.procedureId }
            val newScheduled = when (scheduled) {
                ScheduledChange.Keep -> entry?.scheduled
                is ScheduledChange.Set -> scheduled.value
            }

            val lines = wanted.map { (procedureId, quantity) ->
                val applied = saved[procedureId]?.appliedAmount
                    ?: rates[procedureId]?.amount
                    ?: throw ValidationError("quantity.$procedureId", "procedure has no price at this place")
                PendingLine(procedureId, quantity, applied)
            }

            if (lines.isEmpty() && newScheduled == null) {
                if (entryId != null) db.deleteEntry(entryId)
                return@write DayEntrySaveResult(entryId = null, placeTotal = MinorAmount(0), scheduled = null)
            }

            val total = sumAmounts(lines.map { lineTotal(it.appliedAmount, it.quantity) })
            dayTotal(db.readOtherPlaceTotals(dateText, placeId) + total)
            if (entryId == null && place.archived) throw ValidationError("placeId", "place is archived")

            val id = entryId ?: db.insertEntry(dateText, placeId, newScheduled)
            if (entry != null && entry.scheduled != newScheduled) db.updateScheduled(id, newScheduled)
            val wantedIds = lines.mapTo(HashSet()) { it.procedureId }
            for (procedureId in saved.keys.sorted()) {
                if (procedureId !in wantedIds) db.deleteLine(id, procedureId)
            }
            for (line in lines) {
                val existing = saved[line.procedureId]
                when {
                    existing == null -> db.insertLine(id, line)
                    existing.quantity != line.quantity -> db.updateLineQuantity(id, line.procedureId, line.quantity)
                }
            }
            DayEntrySaveResult(entryId = id, placeTotal = total, scheduled = newScheduled)
        }
    }

    /**
     * Sets only the scheduled count (A) of the day at the place; lines are not touched.
     * Creates the entry when there is none and [value] is not null (not at an archived place); a null [value] on an entry
     * without lines deletes the entry. Same generation check as [save].
     */
    suspend fun setScheduled(
        date: LocalDate,
        placeId: Long,
        value: Int?,
        openedGeneration: Long,
    ): DayEntrySaveResult {
        val dateText = date.toStorageText()
        validateScheduled(value)
        return store.write { db ->
            if (db.readDataGeneration() != openedGeneration) throw StaleGeneration()
            val place = db.readPlace(placeId) ?: throw ValidationError("placeId", "place not found")
            val entry = db.findEntry(dateText, placeId)
            if (entry == null) {
                if (value == null) return@write DayEntrySaveResult(entryId = null, placeTotal = MinorAmount(0), scheduled = null)
                if (place.archived) throw ValidationError("placeId", "place is archived")
                val id = db.insertEntry(dateText, placeId, value)
                return@write DayEntrySaveResult(entryId = id, placeTotal = MinorAmount(0), scheduled = value)
            }
            val lines = db.readSavedLines(entry.id)
            if (value == null && lines.isEmpty()) {
                db.deleteEntry(entry.id)
                return@write DayEntrySaveResult(entryId = null, placeTotal = MinorAmount(0), scheduled = null)
            }
            if (entry.scheduled != value) db.updateScheduled(entry.id, value)
            DayEntrySaveResult(
                entryId = entry.id,
                placeTotal = sumAmounts(lines.map { lineTotal(it.appliedAmount, it.quantity) }),
                scheduled = value,
            )
        }
    }

    fun monthMarks(month: YearMonth): Flow<Map<LocalDate, List<DayMark>>> = marks(month.firstDay, month.lastDay)

    fun marks(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, List<DayMark>>> {
        if (to < from) throw ValidationError("period", "end date is before the start date")
        val fromText = from.toStorageText()
        val toText = to.toStorageText()
        return store.revision.map { store.read { db -> db.readMarks(fromText, toText) } }.distinctUntilChanged()
    }

    fun dayCards(date: LocalDate): Flow<DaySummary> {
        val dateText = date.toStorageText()
        return store.revision.map { store.read { db -> db.readDayCards(date, dateText) } }.distinctUntilChanged()
    }
}

private fun validateScheduled(value: Int?) {
    if (value != null && value < 0) throw ValidationError("scheduled", "scheduled count cannot be negative")
}

private class StoredEntry(val id: Long, val scheduled: Int?)

private class PendingLine(val procedureId: Long, val quantity: Int, val appliedAmount: MinorAmount)

private class SavedLine(
    val procedureId: Long,
    val procedureName: String,
    val procedureArchived: Boolean,
    val quantity: Int,
    val appliedAmount: MinorAmount,
)

private class ActiveRate(val procedureId: Long, val procedureName: String, val amount: MinorAmount)

private val placeOrder = compareBy<Place>({ it.name.lowercase() }, { it.id })

private fun SQLiteConnection.readPlace(placeId: Long): Place? =
    query(
        "SELECT id, name, address, color, archived FROM place WHERE id = ?",
        binder = { bindLong(1, placeId) },
        mapper = {
            Place(
                id = it.getLong(0),
                name = it.getText(1),
                address = it.getTextOrNull(2),
                color = it.getLong(3),
                archived = it.getLong(4) != 0L,
            )
        },
    ).singleOrNull()

private fun SQLiteConnection.findEntry(dateText: String, placeId: Long): StoredEntry? =
    query(
        "SELECT id, scheduledCount FROM work_entry WHERE localDate = ? AND placeId = ?",
        binder = {
            bindText(1, dateText)
            bindLong(2, placeId)
        },
        mapper = { StoredEntry(it.getLong(0), it.getLongOrNull(1)?.toInt()) },
    ).singleOrNull()

private fun SQLiteConnection.readSavedLines(entryId: Long): List<SavedLine> =
    query(
        """
        SELECT l.procedureId, p.name, p.archived, l.quantity, l.appliedAmountMinor
        FROM work_line l JOIN procedure p ON p.id = l.procedureId
        WHERE l.entryId = ?
        ORDER BY l.procedureId
        """.trimIndent(),
        binder = { bindLong(1, entryId) },
        mapper = {
            SavedLine(
                procedureId = it.getLong(0),
                procedureName = it.getText(1),
                procedureArchived = it.getLong(2) != 0L,
                quantity = it.getLong(3).toInt(),
                appliedAmount = MinorAmount(it.getLong(4)),
            )
        },
    )

private fun SQLiteConnection.readActiveRates(placeId: Long): List<ActiveRate> =
    query(
        """
        SELECT r.procedureId, p.name, r.amountMinor
        FROM place_rate r JOIN procedure p ON p.id = r.procedureId
        WHERE r.placeId = ? AND p.archived = 0
        ORDER BY r.procedureId
        """.trimIndent(),
        binder = { bindLong(1, placeId) },
        mapper = { ActiveRate(it.getLong(0), it.getText(1), MinorAmount(it.getLong(2))) },
    )

private fun SQLiteConnection.readOtherPlaceTotals(dateText: String, placeId: Long): List<MinorAmount> =
    query(
        """
        SELECT l.entryId, l.quantity, l.appliedAmountMinor
        FROM work_line l JOIN work_entry e ON e.id = l.entryId
        WHERE e.localDate = ? AND e.placeId <> ?
        ORDER BY l.entryId
        """.trimIndent(),
        binder = {
            bindText(1, dateText)
            bindLong(2, placeId)
        },
        mapper = { it.getLong(0) to lineTotal(MinorAmount(it.getLong(2)), it.getLong(1).toInt()) },
    ).groupBy({ it.first }, { it.second }).values.map { sumAmounts(it) }

private fun SQLiteConnection.readEditor(date: LocalDate, dateText: String, placeId: Long): DayEntryEditorData {
    val generation = readDataGeneration()
    val place = readPlace(placeId) ?: throw ValidationError("placeId", "place not found")
    val entry = findEntry(dateText, placeId)
    val entryId = entry?.id
    val saved = if (entryId == null) emptyMap() else readSavedLines(entryId).associateBy { it.procedureId }
    val rates = readActiveRates(placeId).associateBy { it.procedureId }
    val rows = (saved.keys + rates.keys).map { procedureId ->
        val line = saved[procedureId]
        val rate = rates[procedureId]
        DayEntryEditorRow(
            procedureId = procedureId,
            procedureName = line?.procedureName ?: checkNotNull(rate).procedureName,
            procedureArchived = line?.procedureArchived ?: false,
            currentRate = rate?.amount,
            savedQuantity = line?.quantity,
            savedAppliedAmount = line?.appliedAmount,
        )
    }.sortedWith(compareBy({ it.procedureName.lowercase() }, { it.procedureId }))
    return DayEntryEditorData(
        date = date,
        place = place,
        generation = generation,
        entryId = entryId,
        rows = rows,
        otherPlacesTotal = sumAmounts(readOtherPlaceTotals(dateText, placeId)),
        scheduled = entry?.scheduled,
    )
}

private fun SQLiteConnection.readMarks(fromText: String, toText: String): Map<LocalDate, List<DayMark>> {
    val rows = query(
        """
        SELECT e.localDate, p.id, p.name, p.color
        FROM work_entry e JOIN place p ON p.id = e.placeId
        WHERE e.localDate BETWEEN ? AND ?
          AND (e.scheduledCount IS NOT NULL OR EXISTS (SELECT 1 FROM work_line l WHERE l.entryId = e.id))
        ORDER BY e.localDate, p.id
        """.trimIndent(),
        binder = {
            bindText(1, fromText)
            bindText(2, toText)
        },
        mapper = { it.getText(0) to DayMark(placeId = it.getLong(1), placeName = it.getText(2), color = it.getLong(3)) },
    )
    return rows.groupBy({ it.first }, { it.second }).entries.associate { (dateText, marks) ->
        parseStorageDate(dateText) to marks.sortedWith(compareBy({ it.placeName.lowercase() }, { it.placeId }))
    }
}

private fun SQLiteConnection.readDayCards(date: LocalDate, dateText: String): DaySummary {
    class Row(val entryId: Long, val place: Place, val scheduled: Int?, val line: DayCardLine?)

    // LEFT JOIN keeps entries that have only a scheduled count; an entry with neither lines nor a count is not shown.
    val rows = query(
        """
        SELECT e.id, p.id, p.name, p.address, p.color, p.archived, e.scheduledCount,
               l.procedureId, pr.name, pr.archived, l.quantity, l.appliedAmountMinor
        FROM work_entry e
        JOIN place p ON p.id = e.placeId
        LEFT JOIN work_line l ON l.entryId = e.id
        LEFT JOIN procedure pr ON pr.id = l.procedureId
        WHERE e.localDate = ?
          AND (l.entryId IS NOT NULL OR e.scheduledCount IS NOT NULL)
        ORDER BY e.id, l.procedureId
        """.trimIndent(),
        binder = { bindText(1, dateText) },
        mapper = {
            val line = if (it.isNull(7)) {
                null
            } else {
                val quantity = it.getLong(10).toInt()
                val applied = MinorAmount(it.getLong(11))
                DayCardLine(
                    procedureId = it.getLong(7),
                    procedureName = it.getText(8),
                    procedureArchived = it.getLong(9) != 0L,
                    quantity = quantity,
                    appliedAmount = applied,
                    lineTotal = lineTotal(applied, quantity),
                )
            }
            Row(
                entryId = it.getLong(0),
                place = Place(
                    id = it.getLong(1),
                    name = it.getText(2),
                    address = it.getTextOrNull(3),
                    color = it.getLong(4),
                    archived = it.getLong(5) != 0L,
                ),
                scheduled = it.getLongOrNull(6)?.toInt(),
                line = line,
            )
        },
    )
    val cards = rows.groupBy { it.entryId }.map { (entryId, entryRows) ->
        val lines = entryRows.mapNotNull { it.line }
            .sortedWith(compareBy({ it.procedureName.lowercase() }, { it.procedureId }))
        PlaceDayCard(
            entryId = entryId,
            place = entryRows.first().place,
            lines = lines,
            placeTotal = sumAmounts(lines.map { it.lineTotal }),
            scheduled = entryRows.first().scheduled,
        )
    }.sortedWith(compareBy(placeOrder) { it.place })
    return DaySummary(date = date, places = cards, dayTotal = dayTotal(cards.map { it.placeTotal }))
}

private fun SQLiteConnection.insertEntry(dateText: String, placeId: Long, scheduled: Int?): Long {
    requireRoom("work_entry", CatalogLimits.MAX_WORK_ENTRIES, "day entries")
    execute("INSERT INTO work_entry (localDate, placeId, scheduledCount) VALUES (?, ?, ?)") {
        bindText(1, dateText)
        bindLong(2, placeId)
        bindLongOrNull(3, scheduled?.toLong())
    }
    return query("SELECT last_insert_rowid()", mapper = { it.getLong(0) }).single()
}

private fun SQLiteConnection.updateScheduled(entryId: Long, scheduled: Int?) {
    execute("UPDATE work_entry SET scheduledCount = ? WHERE id = ?") {
        bindLongOrNull(1, scheduled?.toLong())
        bindLong(2, entryId)
    }
}

private fun SQLiteConnection.deleteEntry(entryId: Long) {
    execute("DELETE FROM work_line WHERE entryId = ?") { bindLong(1, entryId) }
    execute("DELETE FROM work_entry WHERE id = ?") { bindLong(1, entryId) }
}

private fun SQLiteConnection.insertLine(entryId: Long, line: PendingLine) {
    requireRoom("work_line", CatalogLimits.MAX_WORK_LINES, "entry lines")
    execute("INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) VALUES (?, ?, ?, ?)") {
        bindLong(1, entryId)
        bindLong(2, line.procedureId)
        bindLong(3, line.quantity.toLong())
        bindLong(4, line.appliedAmount.minor)
    }
}

private fun SQLiteConnection.updateLineQuantity(entryId: Long, procedureId: Long, quantity: Int) {
    execute("UPDATE work_line SET quantity = ? WHERE entryId = ? AND procedureId = ?") {
        bindLong(1, quantity.toLong())
        bindLong(2, entryId)
        bindLong(3, procedureId)
    }
}

private fun SQLiteConnection.deleteLine(entryId: Long, procedureId: Long) {
    execute("DELETE FROM work_line WHERE entryId = ? AND procedureId = ?") {
        bindLong(1, entryId)
        bindLong(2, procedureId)
    }
}
