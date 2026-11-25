package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.execute
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.db.query
import app.servicecalendar.domain.AmountOutOfRange
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * Day entries on top of a real database.
 *
 * Fixture: "Огляд" costs 12.50 at A and 20.00 at B; "Перев'язка" costs 7.75 at A.
 */
class WorkEntryRepositoryTest {
    private val green = 0xFF2E7D32L
    private val blue = 0xFF1565C0L

    private val nov1 = LocalDate(2026, 11, 1)
    private val nov2 = LocalDate(2026, 11, 2)

    /** IDs of the fixture rows. */
    private class Fixture(
        val exam: Long,
        val dressing: Long,
        val placeA: Long,
        val placeB: Long,
    )

    private class Env(
        val repo: WorkEntryRepository,
        val catalog: CatalogRepository,
        val store: DataStore,
        val path: String,
        val f: Fixture,
    )

    private fun entryTest(block: suspend CoroutineScope.(Env) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path)
                try {
                    withTimeout(60_000) {
                        val catalog = CatalogRepository(store)
                        val exam = catalog.createProcedure("Огляд")
                        val dressing = catalog.createProcedure("Перев'язка")
                        val a = catalog.savePlace(placeDraft("Клініка А", mapOf(exam to 1250L, dressing to 775L), green))
                        val b = catalog.savePlace(placeDraft("Клініка Б", mapOf(exam to 2000L), blue))
                        block(Env(WorkEntryRepository(store), catalog, store, path, Fixture(exam, dressing, a, b)))
                    }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    private fun placeDraft(name: String, rates: Map<Long, Long>, color: Long, id: Long? = null) =
        PlaceDraft(id = id, name = name, address = null, color = color, rates = rates)

    private fun SQLiteConnection.count(sql: String): Long = query(sql, mapper = { it.getLong(0) }).single()

    /** Saves with the generation read when the editor is opened, as the UI does. */
    private suspend fun Env.saveDay(date: LocalDate, placeId: Long, quantities: Map<Long, Int>): DayEntrySaveResult {
        val editor = repo.loadEditor(date, placeId)
        return repo.save(date, placeId, quantities, editor.generation)
    }

    private fun DayEntryEditorData.row(procedureId: Long): DayEntryEditorRow =
        rows.single { it.procedureId == procedureId }

    private fun DaySummary.card(placeId: Long): PlaceDayCard = places.single { it.place.id == placeId }

    private fun PlaceDayCard.line(procedureId: Long): DayCardLine = lines.single { it.procedureId == procedureId }

    private fun amount(minor: Long) = MinorAmount(minor)

    private suspend fun Env.changeRates(placeId: Long, name: String, color: Long, rates: Map<Long, Long>) {
        catalog.savePlace(placeDraft(name, rates, color, id = placeId))
    }

    @Test
    fun `one entry per date and place and reopening loads it`() = entryTest { env ->
        val f = env.f
        val before = env.store.revision.value
        val result = env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        assertNotNull(result.entryId)
        assertEquals(amount(3750), result.placeTotal)

        // Saving the same date and place again updates the entry instead of creating a second one.
        val again = env.saveDay(nov1, f.placeA, mapOf(f.exam to 4))
        assertEquals(result.entryId, again.entryId)
        assertEquals(1L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })
        assertEquals(1L, env.store.read { it.count("SELECT COUNT(*) FROM work_line") })
        // Two saves are exactly two write blocks; reading an editor does not change the revision.
        assertEquals(before + 2, env.store.revision.value)

        env.store.close()
        val reopened = DataStore(env.path)
        try {
            val editor = WorkEntryRepository(reopened).loadEditor(nov1, f.placeA)
            assertEquals(result.entryId, editor.entryId)
            assertEquals(4, editor.row(f.exam).savedQuantity)
            assertEquals(amount(1250), editor.row(f.exam).savedAppliedAmount)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `editor lists rated procedures and saved lines with generation and place`() = entryTest { env ->
        val f = env.f
        val unrated = env.catalog.createProcedure("Без тарифу")

        val empty = env.repo.loadEditor(nov1, f.placeA)
        assertEquals(nov1, empty.date)
        assertEquals("Клініка А", empty.place.name)
        assertEquals(green, empty.place.color)
        assertEquals(1L, empty.generation)
        assertNull(empty.entryId)
        assertEquals(amount(0), empty.otherPlacesTotal)
        // A procedure with no rate at the place and no history is not shown; rows are ordered by name.
        assertEquals(listOf(f.exam, f.dressing), empty.rows.map { it.procedureId })
        assertTrue(empty.rows.none { it.procedureId == unrated })
        val exam = empty.row(f.exam)
        assertEquals("Огляд", exam.procedureName)
        assertEquals(amount(1250), exam.currentRate)
        assertNull(exam.savedQuantity)
        assertNull(exam.savedAppliedAmount)
        assertEquals(amount(1250), exam.price)

        env.saveDay(nov1, f.placeB, mapOf(f.exam to 2))
        env.saveDay(nov1, f.placeA, mapOf(f.dressing to 2))
        val loaded = env.repo.loadEditor(nov1, f.placeA)
        assertNotNull(loaded.entryId)
        assertEquals(amount(4000), loaded.otherPlacesTotal)
        assertEquals(2, loaded.row(f.dressing).savedQuantity)
        assertEquals(amount(775), loaded.row(f.dressing).savedAppliedAmount)
        assertNull(loaded.row(f.exam).savedQuantity)
    }

    @Test
    fun `day cards totals and month marks for two places`() = entryTest { env ->
        val f = env.f
        assertEquals(amount(3750), env.saveDay(nov1, f.placeA, mapOf(f.exam to 3)).placeTotal)
        assertEquals(amount(4000), env.saveDay(nov1, f.placeB, mapOf(f.exam to 2)).placeTotal)

        val day = env.repo.dayCards(nov1).first()
        assertEquals(nov1, day.date)
        assertEquals(listOf(f.placeA, f.placeB), day.places.map { it.place.id })
        assertEquals(amount(3750), day.card(f.placeA).placeTotal)
        assertEquals(amount(4000), day.card(f.placeB).placeTotal)
        assertEquals(amount(7750), day.dayTotal)

        // Second procedure at A: "Перев'язка" 2 x 7.75 = 15.50, so A = 53.00 and the day = 93.00.
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        val extended = env.repo.dayCards(nov1).first()
        val a = extended.card(f.placeA)
        assertEquals(listOf(f.exam, f.dressing), a.lines.map { it.procedureId })
        val dressing = a.line(f.dressing)
        assertEquals("Перев'язка", dressing.procedureName)
        assertEquals(2, dressing.quantity)
        assertEquals(amount(775), dressing.appliedAmount)
        assertEquals(amount(1550), dressing.lineTotal)
        assertEquals(amount(3750), a.line(f.exam).lineTotal)
        assertEquals(amount(5300), a.placeTotal)
        assertEquals(amount(9300), extended.dayTotal)

        val marks = env.repo.monthMarks(YearMonth(2026, 11)).first()
        assertEquals(listOf(nov1), marks.keys.toList())
        assertEquals(listOf(f.placeA to green, f.placeB to blue), marks.getValue(nov1).map { it.placeId to it.color })
        assertEquals(listOf("Клініка А", "Клініка Б"), marks.getValue(nov1).map { it.placeName })
    }

    @Test
    fun `zero or absent quantity removes the line`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        env.saveDay(nov1, f.placeB, mapOf(f.exam to 2))

        // Explicit zero: "Огляд" is removed, "Перев'язка" stays, the day is 55.50.
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 0, f.dressing to 2))
        var day = env.repo.dayCards(nov1).first()
        assertEquals(listOf(f.dressing), day.card(f.placeA).lines.map { it.procedureId })
        assertEquals(amount(1550), day.card(f.placeA).placeTotal)
        assertEquals(amount(5550), day.dayTotal)

        // A missing (cleared) field behaves the same way.
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        env.saveDay(nov1, f.placeA, mapOf(f.dressing to 2))
        day = env.repo.dayCards(nov1).first()
        assertEquals(listOf(f.dressing), day.card(f.placeA).lines.map { it.procedureId })
        assertEquals(amount(5550), day.dayTotal)
        val examLinesInA = env.store.read {
            it.count(
                "SELECT COUNT(*) FROM work_line l JOIN work_entry e ON e.id = l.entryId " +
                    "WHERE l.procedureId = ${f.exam} AND e.placeId = ${f.placeA}",
            )
        }
        assertEquals(0L, examLinesInA)
    }

    @Test
    fun `entry is deleted in the same save when no lines remain`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.dressing to 2))
        env.saveDay(nov1, f.placeB, mapOf(f.exam to 2))

        val before = env.store.revision.value
        val result = env.saveDay(nov1, f.placeA, mapOf(f.dressing to 0))
        assertNull(result.entryId)
        assertEquals(amount(0), result.placeTotal)
        assertEquals(before + 1, env.store.revision.value)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry WHERE placeId = ${f.placeA}") })
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_line WHERE procedureId = ${f.dressing}") })

        val day = env.repo.dayCards(nov1).first()
        assertEquals(listOf(f.placeB), day.places.map { it.place.id })
        assertEquals(amount(4000), day.dayTotal)
        assertEquals(listOf(f.placeB), env.repo.monthMarks(YearMonth(2026, 11)).first().getValue(nov1).map { it.placeId })
        assertNull(env.repo.loadEditor(nov1, f.placeA).entryId)

        // Saving an empty editor that has no entry creates nothing.
        assertNull(env.saveDay(nov2, f.placeA, emptyMap()).entryId)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry WHERE localDate = '2026-11-02'") })
    }

    @Test
    fun `changing the current rate keeps the applied amount of saved lines`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        env.changeRates(f.placeA, "Клініка А", green, mapOf(f.exam to 1500L, f.dressing to 775L))

        val editor = env.repo.loadEditor(nov1, f.placeA)
        assertEquals(amount(1500), editor.row(f.exam).currentRate)
        assertEquals(amount(1250), editor.row(f.exam).savedAppliedAmount)
        assertEquals(amount(1250), editor.row(f.exam).price)

        // Saving the same quantity again keeps the historical rate.
        env.repo.save(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2), editor.generation)
        var a = env.repo.dayCards(nov1).first().card(f.placeA)
        assertEquals(amount(1250), a.line(f.exam).appliedAmount)
        assertEquals(amount(3750), a.line(f.exam).lineTotal)

        // Changing the quantity does not touch the rate either.
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 4, f.dressing to 2))
        a = env.repo.dayCards(nov1).first().card(f.placeA)
        assertEquals(amount(1250), a.line(f.exam).appliedAmount)
        assertEquals(amount(5000), a.line(f.exam).lineTotal)
    }

    @Test
    fun `re-adding a procedure after a saved deletion takes the current rate`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        env.saveDay(nov1, f.placeB, mapOf(f.exam to 2))
        env.changeRates(f.placeA, "Клініка А", green, mapOf(f.exam to 1500L, f.dressing to 775L))

        env.saveDay(nov1, f.placeA, mapOf(f.exam to 0, f.dressing to 2))
        val reopened = env.repo.loadEditor(nov1, f.placeA)
        assertNull(reopened.row(f.exam).savedAppliedAmount)
        assertEquals(amount(1500), reopened.row(f.exam).price)

        env.repo.save(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2), reopened.generation)
        val day = env.repo.dayCards(nov1).first()
        val a = day.card(f.placeA)
        assertEquals(amount(1500), a.line(f.exam).appliedAmount)
        assertEquals(amount(4500), a.line(f.exam).lineTotal)
        assertEquals(amount(1550), a.line(f.dressing).lineTotal)
        assertEquals(amount(10050), day.dayTotal)
    }

    @Test
    fun `negative quantity is rejected before any write`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        val editor = env.repo.loadEditor(nov1, f.placeA)
        val revision = env.store.revision.value

        val e = assertFailsWith<ValidationError> {
            env.repo.save(nov1, f.placeA, mapOf(f.exam to 5, f.dressing to -1), editor.generation)
        }
        assertEquals("quantity.${f.dressing}", e.field)
        assertEquals(revision, env.store.revision.value)
        assertEquals(3, env.repo.loadEditor(nov1, f.placeA).row(f.exam).savedQuantity)
    }

    @Test
    fun `quantity for a procedure without a rate in the place is rejected`() = entryTest { env ->
        val f = env.f
        val editor = env.repo.loadEditor(nov1, f.placeB)
        val e = assertFailsWith<ValidationError> {
            env.repo.save(nov1, f.placeB, mapOf(f.exam to 1, f.dressing to 1), editor.generation)
        }
        assertEquals("quantity.${f.dressing}", e.field)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })
        // Zero for such a procedure is accepted.
        env.repo.save(nov1, f.placeB, mapOf(f.exam to 1, f.dressing to 0), editor.generation)
        assertEquals(1L, env.store.read { it.count("SELECT COUNT(*) FROM work_line") })
    }

    @Test
    fun `unknown place is rejected`() = entryTest { env ->
        assertEquals("placeId", assertFailsWith<ValidationError> { env.repo.loadEditor(nov1, 9999L) }.field)
        assertEquals(
            "placeId",
            assertFailsWith<ValidationError> { env.repo.save(nov1, 9999L, mapOf(env.f.exam to 1), 1L) }.field,
        )
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })
    }

    @Test
    fun `overflow of a line or of the day is rejected and nothing is written`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 1))
        val huge = Long.MAX_VALUE / 2 + 1
        env.store.write { db ->
            db.execute("UPDATE place_rate SET amountMinor = ? WHERE placeId = ? AND procedureId = ?") {
                bindLong(1, huge)
                bindLong(2, f.placeB)
                bindLong(3, f.exam)
            }
        }
        val revision = env.store.revision.value

        // Line: huge x 2 > Long.MAX_VALUE.
        assertFailsWith<AmountOutOfRange> { env.saveDay(nov1, f.placeB, mapOf(f.exam to 2)) }
        assertEquals(revision, env.store.revision.value)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry WHERE placeId = ${f.placeB}") })

        // Day: A on 01.11 becomes huge; B's line (huge x 1) fits alone but the day total huge + huge does not.
        env.store.write { db ->
            db.execute("UPDATE work_line SET appliedAmountMinor = ?") { bindLong(1, huge) }
        }
        val revision2 = env.store.revision.value
        assertFailsWith<AmountOutOfRange> { env.saveDay(nov1, f.placeB, mapOf(f.exam to 1)) }
        assertEquals(revision2, env.store.revision.value)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry WHERE placeId = ${f.placeB}") })
        // Another day with the same rate saves fine: the limit is the day total, not the line.
        assertNotNull(env.saveDay(nov2, f.placeB, mapOf(f.exam to 1)).entryId)
    }

    @Test
    fun `failure in the middle of a save leaves no partial data`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        // The database fails when inserting the "Перев'язка" line, after the "Огляд" line was already changed.
        env.store.write { db ->
            db.execSQL(
                "CREATE TRIGGER fail_dressing BEFORE INSERT ON work_line WHEN NEW.procedureId = ${f.dressing} " +
                    "BEGIN SELECT RAISE(ABORT, 'failure in the middle of a save'); END",
            )
        }
        val revision = env.store.revision.value

        assertFailsWith<Exception> { env.saveDay(nov1, f.placeA, mapOf(f.exam to 5, f.dressing to 2)) }
        assertFailsWith<Exception> { env.saveDay(nov2, f.placeA, mapOf(f.exam to 1, f.dressing to 1)) }

        assertEquals(revision, env.store.revision.value)
        val editor = env.repo.loadEditor(nov1, f.placeA)
        assertEquals(3, editor.row(f.exam).savedQuantity)
        assertNull(editor.row(f.dressing).savedQuantity)
        assertEquals(1L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })
        assertEquals(1L, env.store.read { it.count("SELECT COUNT(*) FROM work_line") })
    }

    @Test
    fun `save with a stale generation is rejected and changes nothing`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        val editor = env.repo.loadEditor(nov1, f.placeA)
        assertEquals(1L, editor.generation)
        // A restore bumped the generation after the editor was opened.
        env.store.write { it.execSQL("UPDATE device_state SET dataGeneration = dataGeneration + 1") }
        val revision = env.store.revision.value

        assertFailsWith<StaleGeneration> {
            env.repo.save(nov1, f.placeA, mapOf(f.exam to 9), editor.generation)
        }
        assertFailsWith<StaleGeneration> {
            env.repo.save(nov2, f.placeA, mapOf(f.exam to 9), editor.generation)
        }
        assertEquals(revision, env.store.revision.value)
        val reopened = env.repo.loadEditor(nov1, f.placeA)
        assertEquals(2L, reopened.generation)
        assertEquals(3, reopened.row(f.exam).savedQuantity)
        assertEquals(1L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })

        // A reopened editor saves normally.
        env.repo.save(nov1, f.placeA, mapOf(f.exam to 9), reopened.generation)
        assertEquals(9, env.repo.loadEditor(nov1, f.placeA).row(f.exam).savedQuantity)
    }

    /**
     * The generation check and the save happen in one `write` block. While the store is busy, a save
     * with the old generation is queued, followed by a "restore" of known data. If the save checked
     * the generation in a separate read, the restore could slip in between the check and the write and
     * the stale draft (9) would overwrite the restored value (3). Correct: either the save runs before
     * the restore (which then replaces it) or it is rejected as stale.
     */
    @Test
    fun `generation check and save happen in one write block`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        val editor = env.repo.loadEditor(nov1, f.placeA)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = async(Dispatchers.Default) {
            env.store.write {
                entered.complete(Unit)
                runBlocking { release.await() }
            }
        }
        entered.await()
        val saving = async(Dispatchers.Default) {
            runCatching { env.repo.save(nov1, f.placeA, mapOf(f.exam to 9), editor.generation) }
        }
        delay(300)
        val restoring = async(Dispatchers.Default) {
            env.store.write { db ->
                db.execSQL("DELETE FROM work_line")
                db.execSQL("DELETE FROM work_entry")
                db.execute("INSERT INTO work_entry (localDate, placeId) VALUES ('2026-11-01', ?)") {
                    bindLong(1, f.placeA)
                }
                db.execute(
                    "INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) " +
                        "VALUES (last_insert_rowid(), ?, 3, 1250)",
                ) { bindLong(1, f.exam) }
                db.execSQL("UPDATE device_state SET dataGeneration = dataGeneration + 1")
            }
        }
        delay(300)
        release.complete(Unit)
        blocker.await()
        restoring.await()
        val outcome = saving.await()
        outcome.exceptionOrNull()?.let { assertTrue(it is StaleGeneration, "unexpected error: $it") }

        val after = env.repo.loadEditor(nov1, f.placeA)
        assertEquals(2L, after.generation)
        assertEquals(3, after.row(f.exam).savedQuantity)
    }

    @Test
    fun `month marks and day cards flows emit again after a save`() = entryTest { env ->
        val f = env.f
        val marks = Channel<Map<LocalDate, List<DayMark>>>(Channel.UNLIMITED)
        val cards = Channel<DaySummary>(Channel.UNLIMITED)
        val marksJob = launch { env.repo.monthMarks(YearMonth(2026, 11)).collect { marks.send(it) } }
        val cardsJob = launch { env.repo.dayCards(nov1).collect { cards.send(it) } }
        try {
            assertTrue(marks.receive().isEmpty())
            val empty = cards.receive()
            assertTrue(empty.places.isEmpty())
            assertEquals(amount(0), empty.dayTotal)

            env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
            assertEquals(listOf(f.placeA), marks.receive().getValue(nov1).map { it.placeId })
            assertEquals(amount(3750), cards.receive().dayTotal)

            env.saveDay(nov1, f.placeB, mapOf(f.exam to 2))
            assertEquals(listOf(f.placeA, f.placeB), marks.receive().getValue(nov1).map { it.placeId })
            assertEquals(amount(7750), cards.receive().dayTotal)

            env.saveDay(nov1, f.placeA, emptyMap())
            assertEquals(listOf(f.placeB), marks.receive().getValue(nov1).map { it.placeId })
            assertEquals(listOf(f.placeB), cards.receive().places.map { it.place.id })
        } finally {
            marksJob.cancel()
            cardsJob.cancel()
        }
    }

    @Test
    fun `month marks cover only the requested month in a stable order`() = entryTest { env ->
        val f = env.f
        // A place with the same color as B: marks are told apart by place.
        val pharmacy = env.catalog.savePlace(placeDraft("аптека", mapOf(f.exam to 100L), blue))
        val oct31 = LocalDate(2026, 10, 31)
        val nov30 = LocalDate(2026, 11, 30)
        val dec1 = LocalDate(2026, 12, 1)
        env.saveDay(oct31, f.placeA, mapOf(f.exam to 1))
        env.saveDay(nov30, f.placeB, mapOf(f.exam to 1))
        env.saveDay(nov30, f.placeA, mapOf(f.exam to 1))
        env.saveDay(nov30, pharmacy, mapOf(f.exam to 1))
        env.saveDay(nov1, f.placeB, mapOf(f.exam to 1))
        env.saveDay(dec1, f.placeA, mapOf(f.exam to 1))

        val november = env.repo.monthMarks(YearMonth(2026, 11)).first()
        assertEquals(listOf(nov1, nov30), november.keys.toList())
        assertEquals(listOf(pharmacy, f.placeA, f.placeB), november.getValue(nov30).map { it.placeId })
        assertEquals(listOf(blue, green, blue), november.getValue(nov30).map { it.color })

        val range = env.repo.marks(oct31, nov1).first()
        assertEquals(listOf(oct31, nov1), range.keys.toList())
        assertFailsWith<ValidationError> { env.repo.marks(nov2, nov1).first() }
    }

    @Test
    fun `archived place and procedure with history stay visible on their day`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        assertEquals(RemovalResult.Archived, env.catalog.removeProcedure(f.dressing))
        assertEquals(RemovalResult.Archived, env.catalog.removePlace(f.placeA))

        val day = env.repo.dayCards(nov1).first()
        val a = day.card(f.placeA)
        assertTrue(a.place.archived)
        assertEquals("Клініка А", a.place.name)
        assertTrue(a.line(f.dressing).procedureArchived)
        assertEquals("Перев'язка", a.line(f.dressing).procedureName)
        assertEquals(amount(5300), a.placeTotal)
        assertEquals(amount(5300), day.dayTotal)
        assertEquals(listOf(f.placeA), env.repo.monthMarks(YearMonth(2026, 11)).first().getValue(nov1).map { it.placeId })

        val editor = env.repo.loadEditor(nov1, f.placeA)
        assertTrue(editor.place.archived)
        val dressing = editor.row(f.dressing)
        assertTrue(dressing.procedureArchived)
        assertNull(dressing.currentRate)
        assertEquals(2, dressing.savedQuantity)
        assertEquals(amount(775), dressing.price)

        // An existing entry of an archived place is edited with historical rates.
        env.repo.save(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 1), editor.generation)
        assertEquals(amount(4525), env.repo.dayCards(nov1).first().card(f.placeA).placeTotal)

        // A new entry cannot be created for an archived place.
        assertEquals(
            "placeId",
            assertFailsWith<ValidationError> { env.saveDay(nov2, f.placeA, mapOf(f.exam to 1)) }.field,
        )
        // An archived procedure that was not in the entry cannot be added.
        env.repo.save(nov1, f.placeA, mapOf(f.exam to 3), editor.generation)
        assertEquals(
            "quantity.${f.dressing}",
            assertFailsWith<ValidationError> {
                env.repo.save(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 1), editor.generation)
            }.field,
        )
    }

    private suspend fun Env.setScheduled(date: LocalDate, placeId: Long, value: Int?): DayEntrySaveResult =
        repo.setScheduled(date, placeId, value, store.currentGeneration())

    private suspend fun Env.storedScheduled(date: LocalDate, placeId: Long): List<Long?> = store.read { db ->
        db.query(
            "SELECT scheduledCount FROM work_entry WHERE localDate = ? AND placeId = ?",
            binder = {
                bindText(1, date.toString())
                bindLong(2, placeId)
            },
            mapper = { if (it.isNull(0)) null else it.getLong(0) },
        )
    }

    @Test
    fun `setScheduled creates updates and clears the scheduled count`() = entryTest { env ->
        val f = env.f
        // Clearing when there is no entry creates nothing.
        assertEquals(DayEntrySaveResult(entryId = null, placeTotal = amount(0), scheduled = null), env.setScheduled(nov1, f.placeA, null))
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })

        val before = env.store.revision.value
        val created = env.setScheduled(nov1, f.placeA, 110)
        assertNotNull(created.entryId)
        assertEquals(amount(0), created.placeTotal)
        assertEquals(110, created.scheduled)
        assertEquals(listOf<Long?>(110), env.storedScheduled(nov1, f.placeA))
        assertEquals(before + 1, env.store.revision.value)

        val updated = env.setScheduled(nov1, f.placeA, 0)
        assertEquals(created.entryId, updated.entryId)
        assertEquals(listOf<Long?>(0), env.storedScheduled(nov1, f.placeA))
        assertEquals(0, env.repo.loadEditor(nov1, f.placeA).scheduled)

        // Without lines, clearing deletes the entry.
        assertNull(env.setScheduled(nov1, f.placeA, null).entryId)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })
        assertTrue(env.repo.dayCards(nov1).first().places.isEmpty())
    }

    @Test
    fun `setScheduled keeps lines and clearing it on an entry with lines keeps the entry`() = entryTest { env ->
        val f = env.f
        val saved = env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        val set = env.setScheduled(nov1, f.placeA, 8)
        assertEquals(saved.entryId, set.entryId)
        assertEquals(amount(5300), set.placeTotal)

        val card = env.repo.dayCards(nov1).first().card(f.placeA)
        assertEquals(8, card.scheduled)
        assertEquals(5L, card.completed)
        assertEquals(62.5, card.percent)
        assertEquals(2, card.lines.size)

        val cleared = env.setScheduled(nov1, f.placeA, null)
        assertEquals(saved.entryId, cleared.entryId)
        assertNull(cleared.scheduled)
        val after = env.repo.dayCards(nov1).first().card(f.placeA)
        assertNull(after.scheduled)
        assertNull(after.percent)
        assertEquals(5L, after.completed)
        assertEquals(amount(5300), after.placeTotal)
    }

    @Test
    fun `setScheduled validates the value the place and the generation`() = entryTest { env ->
        val f = env.f
        assertEquals("scheduled", assertFailsWith<ValidationError> { env.setScheduled(nov1, f.placeA, -1) }.field)
        assertEquals("placeId", assertFailsWith<ValidationError> { env.setScheduled(nov1, 999, 3) }.field)
        assertFailsWith<StaleGeneration> { env.repo.setScheduled(nov1, f.placeA, 3, openedGeneration = 99) }
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })

        env.setScheduled(nov1, f.placeA, Int.MAX_VALUE)
        assertEquals(listOf<Long?>(Int.MAX_VALUE.toLong()), env.storedScheduled(nov1, f.placeA))

        // An archived place keeps its existing entry editable but gets no new one.
        assertEquals(RemovalResult.Archived, env.catalog.removePlace(f.placeA))
        env.setScheduled(nov1, f.placeA, 4)
        assertEquals(listOf<Long?>(4), env.storedScheduled(nov1, f.placeA))
        assertEquals("placeId", assertFailsWith<ValidationError> { env.setScheduled(nov2, f.placeA, 4) }.field)
    }

    @Test
    fun `save with only a scheduled count keeps an entry without lines`() = entryTest { env ->
        val f = env.f
        val editor = env.repo.loadEditor(nov1, f.placeA)
        assertNull(editor.scheduled)
        val result = env.repo.save(nov1, f.placeA, emptyMap(), editor.generation, ScheduledChange.Set(12))
        assertNotNull(result.entryId)
        assertEquals(amount(0), result.placeTotal)
        assertEquals(12, result.scheduled)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_line") })
        assertEquals(12, env.repo.loadEditor(nov1, f.placeA).scheduled)

        // Clearing it through save as well, with no lines, deletes the entry.
        val cleared = env.repo.save(nov1, f.placeA, mapOf(f.exam to 0), editor.generation, ScheduledChange.Set(null))
        assertNull(cleared.entryId)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })

        assertEquals(
            "scheduled",
            assertFailsWith<ValidationError> {
                env.repo.save(nov1, f.placeA, emptyMap(), editor.generation, ScheduledChange.Set(-5))
            }.field,
        )
    }

    @Test
    fun `save without a scheduled argument keeps the stored count`() = entryTest { env ->
        val f = env.f
        env.setScheduled(nov1, f.placeA, 10)
        val saved = env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        assertEquals(10, saved.scheduled)
        assertEquals(listOf<Long?>(10), env.storedScheduled(nov1, f.placeA))

        env.saveDay(nov1, f.placeA, mapOf(f.exam to 7, f.dressing to 1))
        val card = env.repo.dayCards(nov1).first().card(f.placeA)
        assertEquals(10, card.scheduled)
        assertEquals(8L, card.completed)
        assertEquals(80.0, card.percent)

        // Set replaces it in the same write as the lines.
        env.repo.save(nov1, f.placeA, mapOf(f.exam to 7), env.store.currentGeneration(), ScheduledChange.Set(14))
        assertEquals(listOf<Long?>(14), env.storedScheduled(nov1, f.placeA))
        assertEquals(50.0, env.repo.dayCards(nov1).first().card(f.placeA).percent)
    }

    @Test
    fun `removing the last line keeps an entry that has a scheduled count`() = entryTest { env ->
        val f = env.f
        val first = env.saveDay(nov1, f.placeA, mapOf(f.exam to 3))
        env.setScheduled(nov1, f.placeA, 5)

        val result = env.saveDay(nov1, f.placeA, mapOf(f.exam to 0))
        assertEquals(first.entryId, result.entryId)
        assertEquals(amount(0), result.placeTotal)
        assertEquals(5, result.scheduled)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_line") })

        val card = env.repo.dayCards(nov1).first().card(f.placeA)
        assertTrue(card.lines.isEmpty())
        assertEquals(0L, card.completed)
        assertEquals(0.0, card.percent)
        assertEquals(amount(0), card.placeTotal)
        assertEquals(listOf(f.placeA), env.repo.monthMarks(YearMonth(2026, 11)).first().getValue(nov1).map { it.placeId })

        // Without a scheduled count the same save deletes the entry, as before.
        env.setScheduled(nov1, f.placeA, null)
        assertEquals(0L, env.store.read { it.count("SELECT COUNT(*) FROM work_entry") })
    }

    @Test
    fun `marks and cards include days with only a scheduled count`() = entryTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeB, mapOf(f.exam to 2))
        env.setScheduled(nov1, f.placeA, 6)
        env.setScheduled(nov2, f.placeB, 0)

        val marks = env.repo.monthMarks(YearMonth(2026, 11)).first()
        assertEquals(listOf(nov1, nov2), marks.keys.sorted())
        assertEquals(listOf(f.placeA to green, f.placeB to blue), marks.getValue(nov1).map { it.placeId to it.color })
        assertEquals(listOf(f.placeB), marks.getValue(nov2).map { it.placeId })

        val day1 = env.repo.dayCards(nov1).first()
        assertEquals(listOf(f.placeA, f.placeB), day1.places.map { it.place.id })
        val a = day1.card(f.placeA)
        assertTrue(a.lines.isEmpty())
        assertEquals(6, a.scheduled)
        assertEquals(0.0, a.percent)
        val b = day1.card(f.placeB)
        assertNull(b.scheduled)
        assertNull(b.percent)
        assertEquals(2L, b.completed)
        assertEquals(amount(4000), day1.dayTotal)

        // Zero scheduled: a card and a mark, but no percent.
        val nov2Card = env.repo.dayCards(nov2).first().card(f.placeB)
        assertEquals(0, nov2Card.scheduled)
        assertNull(nov2Card.percent)
    }

    @Test
    fun `an open day cards flow emits after setScheduled`() = entryTest { env ->
        val f = env.f
        val seen = Channel<DaySummary>(Channel.UNLIMITED)
        val job = launch { env.repo.dayCards(nov1).collect { seen.send(it) } }
        try {
            assertTrue(withTimeout(10_000) { seen.receive() }.places.isEmpty())
            env.setScheduled(nov1, f.placeA, 3)
            assertEquals(listOf(3), withTimeout(10_000) { seen.receive() }.places.map { it.scheduled })
            env.setScheduled(nov1, f.placeA, 4)
            assertEquals(listOf(4), withTimeout(10_000) { seen.receive() }.places.map { it.scheduled })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `save with a stale generation does not touch the scheduled count`() = entryTest { env ->
        val f = env.f
        env.setScheduled(nov1, f.placeA, 9)
        assertFailsWith<StaleGeneration> {
            env.repo.save(nov1, f.placeA, emptyMap(), openedGeneration = 99, scheduled = ScheduledChange.Set(null))
        }
        assertEquals(listOf<Long?>(9), env.storedScheduled(nov1, f.placeA))
    }

    @Test
    fun `an entry with only a scheduled count still archives its place on removal`() = entryTest { env ->
        val f = env.f
        env.setScheduled(nov1, f.placeB, 2)
        assertEquals(RemovalResult.Archived, env.catalog.removePlace(f.placeB))
    }
}
