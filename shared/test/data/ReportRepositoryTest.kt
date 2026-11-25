package app.servicecalendar.data

import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.execute
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.domain.AmountOutOfRange
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/**
 * Period reports on top of a real database.
 *
 * Fixture: "Огляд" costs 12.50 at A and 20.00 at B; entries are 01.11 A x3, 01.11 B x2, 02.11 A x1,
 * 03.11 B x4. Some tests add "Перев'язка" at A (7.75) x2 on 01.11.
 */
class ReportRepositoryTest {
    private val green = 0xFF2E7D32L
    private val blue = 0xFF1565C0L

    private val nov1 = LocalDate(2026, 11, 1)
    private val nov2 = LocalDate(2026, 11, 2)
    private val nov3 = LocalDate(2026, 11, 3)

    private class Fixture(
        val exam: Long,
        val dressing: Long,
        val placeA: Long,
        val placeB: Long,
    )

    private class Env(
        val repo: ReportRepository,
        val entries: WorkEntryRepository,
        val catalog: CatalogRepository,
        val store: DataStore,
        val f: Fixture,
    )

    /** Runs [block] with the fixture already entered: the 01.11-02.11 report gives A = 5000, B = 4000. */
    private fun reportTest(block: suspend CoroutineScope.(Env) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path)
                try {
                    withTimeout(60_000) {
                        val catalog = CatalogRepository(store)
                        val entries = WorkEntryRepository(store)
                        val exam = catalog.createProcedure("Огляд")
                        val dressing = catalog.createProcedure("Перев'язка")
                        val a = catalog.savePlace(draft("Клініка А", mapOf(exam to 1250L, dressing to 775L), green))
                        val b = catalog.savePlace(draft("Клініка Б", mapOf(exam to 2000L), blue))
                        val env = Env(ReportRepository(store), entries, catalog, store, Fixture(exam, dressing, a, b))
                        env.saveDay(nov1, a, mapOf(exam to 3))
                        env.saveDay(nov1, b, mapOf(exam to 2))
                        env.saveDay(nov2, a, mapOf(exam to 1))
                        env.saveDay(nov3, b, mapOf(exam to 4))
                        block(env)
                    }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    private fun draft(name: String, rates: Map<Long, Long>, color: Long, id: Long? = null) =
        PlaceDraft(id = id, name = name, address = null, color = color, rates = rates)

    private suspend fun Env.saveDay(date: LocalDate, placeId: Long, quantities: Map<Long, Int>) {
        val editor = entries.loadEditor(date, placeId)
        entries.save(date, placeId, quantities, editor.generation)
    }

    private fun PeriodReport.row(placeId: Long): PlaceReportRow = places.single { it.place.id == placeId }

    private fun amount(minor: Long) = MinorAmount(minor)

    @Test
    fun `period from first to second November gives A 5000 and B 4000`() = reportTest { env ->
        val report = env.repo.report(nov1, nov2)
        assertEquals(nov1, report.from)
        assertEquals(nov2, report.to)
        assertEquals(listOf(env.f.placeA, env.f.placeB), report.places.map { it.place.id })
        assertEquals(amount(5000), report.row(env.f.placeA).total)
        assertEquals(amount(4000), report.row(env.f.placeB).total)
        assertEquals(amount(9000), report.total)
        // Days with entries: A on 01.11 and 02.11, B on 01.11 only.
        assertEquals(2, report.row(env.f.placeA).entryDays)
        assertEquals(1, report.row(env.f.placeB).entryDays)
        assertEquals("Клініка А", report.row(env.f.placeA).place.name)
        assertEquals(green, report.row(env.f.placeA).place.color)
    }

    @Test
    fun `single day second November gives only A 1250`() = reportTest { env ->
        val report = env.repo.report(nov2, nov2)
        assertEquals(listOf(env.f.placeA), report.places.map { it.place.id })
        assertEquals(amount(1250), report.row(env.f.placeA).total)
        assertEquals(1, report.row(env.f.placeA).entryDays)
        assertEquals(amount(1250), report.total)
    }

    @Test
    fun `both bounds are inclusive and the day after the end is excluded`() = reportTest { env ->
        // Start is inclusive: 01.11-01.11 sees both places.
        val first = env.repo.report(nov1, nov1)
        assertEquals(amount(3750), first.row(env.f.placeA).total)
        assertEquals(amount(4000), first.row(env.f.placeB).total)
        // End is inclusive: 03.11-03.11 sees only B = 4 x 20.00.
        val last = env.repo.report(nov3, nov3)
        assertEquals(listOf(env.f.placeB), last.places.map { it.place.id })
        assertEquals(amount(8000), last.row(env.f.placeB).total)
        // 03.11 is outside 01.11-02.11 but inside 01.11-03.11.
        assertEquals(amount(4000), env.repo.report(nov1, nov2).row(env.f.placeB).total)
        assertEquals(amount(12000), env.repo.report(nov1, nov3).row(env.f.placeB).total)
        // Ranges just outside the entries are empty.
        assertTrue(env.repo.report(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)).places.isEmpty())
        assertTrue(env.repo.report(LocalDate(2026, 11, 4), LocalDate(2026, 11, 30)).places.isEmpty())
    }

    @Test
    fun `empty period gives no places and zero total`() = reportTest { env ->
        val report = env.repo.report(LocalDate(2026, 12, 1), LocalDate(2026, 12, 31))
        assertTrue(report.places.isEmpty())
        assertEquals(amount(0), report.total)
        assertEquals(LocalDate(2026, 12, 1), report.from)
        assertEquals(LocalDate(2026, 12, 31), report.to)
    }

    @Test
    fun `end before start is a period validation error before touching the database`() = reportTest { env ->
        val revision = env.store.revision.value
        assertEquals("period", assertFailsWith<ValidationError> { env.repo.report(nov2, nov1) }.field)
        // The flow also fails at call time, without subscribing to the database.
        assertEquals("period", assertFailsWith<ValidationError> { env.repo.reportFlow(nov2, nov1) }.field)
        assertEquals(revision, env.store.revision.value)
        // A single day (start = end) is a valid period.
        env.repo.report(nov2, nov2)
    }

    @Test
    fun `old report keeps historical amounts after the current rate changes`() = reportTest { env ->
        val f = env.f
        env.catalog.savePlace(draft("Клініка А", mapOf(f.exam to 9900L, f.dressing to 775L), green, id = f.placeA))
        val report = env.repo.report(nov1, nov2)
        assertEquals(amount(5000), report.row(f.placeA).total)
        assertEquals(amount(4000), report.row(f.placeB).total)
        // The existing 02.11 line keeps 12.50, while the new "Перев'язка" line uses the current rate of 7.75.
        env.saveDay(nov2, f.placeA, mapOf(f.exam to 1, f.dressing to 1))
        assertEquals(amount(5000 + 775), env.repo.report(nov1, nov2).row(f.placeA).total)
    }

    @Test
    fun `extended set gives A 6550 and B 4000`() = reportTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        val report = env.repo.report(nov1, nov2)
        assertEquals(amount(6550), report.row(f.placeA).total)
        assertEquals(amount(4000), report.row(f.placeB).total)
        assertEquals(amount(10550), report.total)
        // 01.11 at A counts as one day, not one per line.
        assertEquals(2, report.row(f.placeA).entryDays)
    }

    @Test
    fun `overflow of a place sum fails with no partial result`() = reportTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 1))
        env.store.write { db ->
            // Push the 02.11 line of A close to Long.MAX_VALUE so that, summed with A's other lines, it overflows.
            db.execute(
                "UPDATE work_line SET appliedAmountMinor = ? WHERE entryId IN " +
                    "(SELECT id FROM work_entry WHERE localDate = ? AND placeId = ?)",
            ) {
                bindLong(1, Long.MAX_VALUE - 10)
                bindText(2, "2026-11-02")
                bindLong(3, f.placeA)
            }
        }
        assertFailsWith<AmountOutOfRange> { env.repo.report(nov1, nov2) }
        assertFailsWith<AmountOutOfRange> { env.repo.reportFlow(nov1, nov2).first() }
        // A period that does not include that line is still computed.
        assertEquals(amount(4000), env.repo.report(nov1, nov1).row(f.placeB).total)
    }

    @Test
    fun `overflow of a single line and of the grand total fails`() = reportTest { env ->
        val f = env.f
        val huge = Long.MAX_VALUE / 2 + 1
        env.store.write { db ->
            // A single line: huge x 2 (B on 01.11) exceeds Long.
            db.execute(
                "UPDATE work_line SET appliedAmountMinor = ? WHERE entryId IN " +
                    "(SELECT id FROM work_entry WHERE localDate = ? AND placeId = ?)",
            ) {
                bindLong(1, huge)
                bindText(2, "2026-11-01")
                bindLong(3, f.placeB)
            }
        }
        assertFailsWith<AmountOutOfRange> { env.repo.report(nov1, nov1) }

        env.store.write { db ->
            // Each place fits on its own but the report total does not: A is about 2^62 and B is 2 x 2^61 = 2^62.
            db.execute(
                "UPDATE work_line SET appliedAmountMinor = ? WHERE entryId IN " +
                    "(SELECT id FROM work_entry WHERE localDate = ? AND placeId = ?)",
            ) {
                bindLong(1, Long.MAX_VALUE / 4 + 1)
                bindText(2, "2026-11-01")
                bindLong(3, f.placeB)
            }
            db.execute(
                "UPDATE work_line SET appliedAmountMinor = ? WHERE entryId IN " +
                    "(SELECT id FROM work_entry WHERE localDate = ? AND placeId = ?)",
            ) {
                bindLong(1, huge)
                bindText(2, "2026-11-02")
                bindLong(3, f.placeA)
            }
        }
        assertFailsWith<AmountOutOfRange> { env.repo.report(nov1, nov2) }
    }

    @Test
    fun `archived places and procedures with history still appear`() = reportTest { env ->
        val f = env.f
        assertEquals(RemovalResult.Archived, env.catalog.removePlace(f.placeB))
        assertEquals(RemovalResult.Archived, env.catalog.removeProcedure(f.exam))
        val report = env.repo.report(nov1, nov2)
        assertEquals(amount(5000), report.row(f.placeA).total)
        assertEquals(amount(4000), report.row(f.placeB).total)
        assertTrue(report.row(f.placeB).place.archived)
        assertFalse(report.row(f.placeA).place.archived)
    }

    @Test
    fun `places with entries only outside the period do not appear`() = reportTest { env ->
        val c = env.catalog.savePlace(draft("Клініка В", mapOf(env.f.exam to 500L), green))
        env.saveDay(LocalDate(2026, 11, 10), c, mapOf(env.f.exam to 1))
        val report = env.repo.report(nov1, nov2)
        assertEquals(listOf(env.f.placeA, env.f.placeB), report.places.map { it.place.id })
        // A place with no entries at all does not appear either.
        env.catalog.savePlace(draft("Порожнє", mapOf(env.f.exam to 100L), blue))
        assertEquals(2, env.repo.report(nov1, nov2).places.size)
        assertEquals(listOf(c), env.repo.report(LocalDate(2026, 11, 10), LocalDate(2026, 11, 10)).places.map { it.place.id })
    }

    @Test
    fun `places are ordered by name ignoring case then by id`() = reportTest { env ->
        val f = env.f
        // Lower-case "арка" must sort before "Бук" even though its code point is higher.
        val arka = env.catalog.savePlace(draft("арка", mapOf(f.exam to 100L), green))
        val buk = env.catalog.savePlace(draft("Бук", mapOf(f.exam to 100L), blue))
        val twinFirst = env.catalog.savePlace(draft("Яблуко", mapOf(f.exam to 100L), green))
        val twinSecond = env.catalog.savePlace(draft("яблуко", mapOf(f.exam to 100L), blue))
        for (id in listOf(twinSecond, twinFirst, buk, arka)) env.saveDay(nov1, id, mapOf(f.exam to 1))
        val report = env.repo.report(nov1, nov1)
        assertEquals(
            // "Клініка" (К) sits between "Бук" (Б) and "Яблуко" (Я); equal names are ordered by id.
            listOf(arka, buk, f.placeA, f.placeB, twinFirst, twinSecond),
            report.places.map { it.place.id },
        )
    }

    @Test
    fun `flow emits at once and again after a save`() = reportTest { env ->
        val f = env.f
        val emissions = Channel<PeriodReport>(Channel.UNLIMITED)
        val job = launch { env.repo.reportFlow(nov1, nov2).collect { emissions.send(it) } }
        try {
            val first = emissions.receive()
            assertEquals(amount(5000), first.row(f.placeA).total)

            env.saveDay(nov2, f.placeA, mapOf(f.exam to 3))
            var latest = emissions.receive()
            while (latest.row(f.placeA).total != amount(3750 + 3750)) latest = emissions.receive()
            assertEquals(amount(3750 + 3750 + 4000), latest.total)

            // A save outside the period bumps the revision but leaves the report unchanged.
            env.saveDay(nov3, f.placeA, mapOf(f.exam to 1))
            env.saveDay(nov2, f.placeA, mapOf(f.exam to 1))
            var after = emissions.receive()
            while (after.row(f.placeA).total != amount(5000)) after = emissions.receive()
            assertEquals(amount(5000), after.row(f.placeA).total)
        } finally {
            job.cancel()
        }
    }

    private suspend fun Env.setA(date: LocalDate, placeId: Long, value: Int?) {
        val editor = entries.loadEditor(date, placeId)
        entries.setScheduled(date, placeId, value, editor.generation)
    }

    @Test
    fun `breakdown per procedure sums quantities and amounts over the period sorted by name`() = reportTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        val report = env.repo.report(nov1, nov2)
        val rowA = report.row(f.placeA)
        assertEquals(listOf("Огляд", "Перев'язка"), rowA.procedures.map { it.name })
        // Огляд: 3 + 1 at 12.50; Перев'язка: 2 at 7.75.
        assertEquals(4L, rowA.procedures[0].quantity)
        assertEquals(amount(5000), rowA.procedures[0].total)
        assertEquals(f.exam, rowA.procedures[0].procedureId)
        assertFalse(rowA.procedures[0].archived)
        assertEquals(2L, rowA.procedures[1].quantity)
        assertEquals(amount(1550), rowA.procedures[1].total)
        assertEquals(amount(6550), rowA.total)
        assertEquals(amount(rowA.procedures.sumOf { it.total.minor }), rowA.total)
        assertEquals(listOf(2L), report.row(f.placeB).procedures.map { it.quantity })
    }

    @Test
    fun `A R and percent per place and overall`() = reportTest { env ->
        val f = env.f
        env.setA(nov1, f.placeA, 10)
        env.setA(nov2, f.placeA, 5)
        env.setA(nov1, f.placeB, 4)
        val report = env.repo.report(nov1, nov2)
        val rowA = report.row(f.placeA)
        assertEquals(15L, rowA.scheduled)
        assertEquals(4L, rowA.completed)
        assertEquals(26.7, rowA.percent)
        val rowB = report.row(f.placeB)
        assertEquals(4L, rowB.scheduled)
        assertEquals(2L, rowB.completed)
        assertEquals(50.0, rowB.percent)
        assertEquals(19L, report.scheduled)
        assertEquals(6L, report.completed)
        assertEquals(31.6, report.percent)
        // Money is unaffected by A.
        assertEquals(amount(9000), report.total)
    }

    @Test
    fun `entries with only A count as days and as scheduled but add no money`() = reportTest { env ->
        val f = env.f
        env.setA(nov2, f.placeB, 7)
        val report = env.repo.report(nov2, nov2)
        val rowB = report.row(f.placeB)
        assertEquals(1, rowB.entryDays)
        assertEquals(amount(0), rowB.total)
        assertTrue(rowB.procedures.isEmpty())
        assertEquals(7L, rowB.scheduled)
        assertEquals(0L, rowB.completed)
        assertEquals(0.0, rowB.percent)
        assertEquals(7L, report.scheduled)
        // Place A has no A, so its quantity is excluded from the overall R.
        assertEquals(0L, report.completed)
        assertEquals(amount(1250), report.total)
    }

    @Test
    fun `zero or missing A gives null percent`() = reportTest { env ->
        val f = env.f
        val noA = env.repo.report(nov1, nov2)
        assertNull(noA.percent)
        assertNull(noA.row(f.placeA).percent)
        assertNull(noA.scheduled)
        assertNull(noA.row(f.placeA).scheduled)
        assertEquals(6L, noA.completed)
        env.setA(nov1, f.placeA, 0)
        val zero = env.repo.report(nov1, nov2)
        assertNull(zero.row(f.placeA).percent)
        assertNull(zero.percent)
        assertEquals(0L, zero.row(f.placeA).scheduled)
        assertEquals(3L, zero.row(f.placeA).completed)
    }

    @Test
    fun `R and percent count only days with A and places without A show all R`() = reportTest { env ->
        val f = env.f
        val nov16 = LocalDate(2026, 11, 16)
        val nov18 = LocalDate(2026, 11, 18)
        val femme = env.catalog.savePlace(draft("femme", mapOf(f.exam to 100L, f.dressing to 100L), green))
        val north = env.catalog.savePlace(draft("Clinica Norte", mapOf(f.exam to 100L), blue))
        env.saveDay(nov18, femme, mapOf(f.exam to 60, f.dressing to 7))
        env.setA(nov18, femme, 110)
        env.saveDay(nov16, femme, mapOf(f.exam to 5))
        env.setA(nov16, femme, 10)
        env.saveDay(nov18, north, mapOf(f.exam to 3))
        val report = env.repo.report(LocalDate(2026, 11, 1), LocalDate(2026, 11, 20))
        val rowFemme = report.row(femme)
        assertEquals(120L, rowFemme.scheduled)
        assertEquals(72L, rowFemme.completed)
        assertEquals(60.0, rowFemme.percent)
        val rowNorth = report.row(north)
        assertNull(rowNorth.scheduled)
        assertEquals(3L, rowNorth.completed)
        assertNull(rowNorth.percent)
        assertEquals(120L, report.scheduled)
        assertEquals(72L, report.completed)
        assertEquals(60.0, report.percent)
        // Per-procedure lines still count all days.
        assertEquals(65L, rowFemme.procedures.single { it.procedureId == f.exam }.quantity)
    }

    @Test
    fun `place with one day with A and one without takes R and percent from the A day only`() = reportTest { env ->
        val f = env.f
        env.setA(nov1, f.placeA, 12)
        val rowA = env.repo.report(nov1, nov2).row(f.placeA)
        assertEquals(12L, rowA.scheduled)
        assertEquals(3L, rowA.completed)
        assertEquals(25.0, rowA.percent)
        assertEquals(2, rowA.entryDays)
        assertEquals(4L, rowA.procedures.single().quantity)
    }

    @Test
    fun `A zero entered on the only day shows A 0 and R with no percent`() = reportTest { env ->
        val f = env.f
        env.setA(nov2, f.placeA, 0)
        val rowA = env.repo.report(nov2, nov2).row(f.placeA)
        assertEquals(0L, rowA.scheduled)
        assertEquals(1L, rowA.completed)
        assertNull(rowA.percent)
    }

    @Test
    fun `several lines do not duplicate A or money`() = reportTest { env ->
        val f = env.f
        env.saveDay(nov1, f.placeA, mapOf(f.exam to 3, f.dressing to 2))
        env.setA(nov1, f.placeA, 10)
        val rowA = env.repo.report(nov1, nov1).row(f.placeA)
        assertEquals(10L, rowA.scheduled)
        assertEquals(5L, rowA.completed)
        assertEquals(50.0, rowA.percent)
        assertEquals(amount(3750 + 1550), rowA.total)
        assertEquals(1, rowA.entryDays)
    }
}
