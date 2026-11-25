package app.servicecalendar.data

import androidx.sqlite.execSQL
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/** Calendar flows (month marks, day cards) and the report flow re-read the data after a restore commit. */
class RestoreRefreshTest {
    private val nov1 = LocalDate(2026, 11, 1)
    private val nov30 = LocalDate(2026, 11, 30)

    private fun storeTest(block: suspend CoroutineScope.(store: DataStore) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path)
                try {
                    withTimeout(60_000) { block(store) }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    /** Collects [flow] into a channel while [body] runs. */
    private suspend fun <T> CoroutineScope.watching(flow: Flow<T>, body: suspend (Channel<T>) -> Unit) {
        val channel = Channel<T>(Channel.UNLIMITED)
        val job = launch { flow.collect { channel.send(it) } }
        try {
            body(channel)
        } finally {
            job.cancel()
        }
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(10_000) { receive() }

    @Test
    fun `calendar and report flows show the restored set after a restore`() = storeTest { store ->
        val catalog = CatalogRepository(store)
        val entries = WorkEntryRepository(store)
        val reports = ReportRepository(store)
        val exam = catalog.createProcedure("Огляд")
        val place = catalog.savePlace(PlaceDraft(null, "Локальне", null, 0xFF2E7D32L, mapOf(exam to 1000L)))
        entries.save(nov1, place, mapOf(exam to 9), entries.loadEditor(nov1, place).generation)

        watching(entries.monthMarks(YearMonth(2026, 11))) { marks ->
            watching(entries.dayCards(nov1)) { cards ->
                watching(reports.reportFlow(nov1, nov30)) { report ->
                    assertEquals(listOf("Локальне"), marks.next().getValue(nov1).map { it.placeName })
                    assertEquals(MinorAmount(9000), cards.next().dayTotal)
                    assertEquals(MinorAmount(9000), report.next().total)

                    // Replace everything with: place A, Огляд x3 at 12.50 on 01.11.
                    store.restore { db ->
                        db.execSQL("DELETE FROM work_line")
                        db.execSQL("DELETE FROM work_entry")
                        db.execSQL("DELETE FROM place_rate")
                        db.execSQL("DELETE FROM place")
                        db.execSQL("DELETE FROM procedure")
                        db.execSQL("INSERT INTO procedure (id, name) VALUES (1, 'Огляд')")
                        db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'A', 4280391411)")
                        db.execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 1, 1250)")
                        db.execSQL("INSERT INTO work_entry (id, localDate, placeId) VALUES (1, '2026-11-01', 1)")
                        db.execSQL(
                            "INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) VALUES (1, 1, 3, 1250)",
                        )
                    }

                    assertEquals(listOf("A"), marks.next().getValue(nov1).map { it.placeName })
                    val day = cards.next()
                    assertEquals(MinorAmount(3750), day.dayTotal)
                    assertEquals(3, day.places.single().lines.single().quantity)
                    val restored = report.next()
                    assertEquals(MinorAmount(3750), restored.total)
                    assertEquals(listOf("A"), restored.places.map { it.place.name })
                }
            }
        }
    }
}
