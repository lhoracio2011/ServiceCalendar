package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.execute
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.db.query
import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.LimitExceeded
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.model.Procedure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Procedure and place catalogs on top of a real database. */
class CatalogRepositoryTest {
    private val green = 0xFF2E7D32L
    private val blue = 0xFF1565C0L

    private fun catalogTest(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend CoroutineScope.(repo: CatalogRepository, store: DataStore, path: String) -> Unit,
    ) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path, dispatcher)
                try {
                    withTimeout(60_000) { block(CatalogRepository(store), store, path) }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    private fun draft(
        name: String,
        rates: Map<Long, Long> = emptyMap(),
        color: Long = green,
        id: Long? = null,
        address: String? = null,
    ) = PlaceDraft(id = id, name = name, address = address, color = color, rates = rates)

    /** Adds history: a day entry for the place with one line for the procedure. */
    private suspend fun DataStore.addHistory(placeId: Long, procedureId: Long, date: String = "2026-03-10") =
        write { db ->
            db.execute("INSERT INTO work_entry (localDate, placeId) VALUES (?, ?)") {
                bindText(1, date)
                bindLong(2, placeId)
            }
            val entryId = db.query("SELECT last_insert_rowid()", mapper = { it.getLong(0) }).single()
            db.execute(
                "INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) VALUES (?, ?, 1, 100)",
            ) {
                bindLong(1, entryId)
                bindLong(2, procedureId)
            }
        }

    private fun SQLiteConnection.count(sql: String): Long = query(sql, mapper = { it.getLong(0) }).single()

    private fun PlaceWithRates.ratesMap(): Map<Long, Long> =
        rates.associate { it.procedureId to it.amountMinor.minor }

    @Test
    fun `one procedure has different rates in two places`() = catalogTest { repo, _, _ ->
        val proc = repo.createProcedure("Чистка")
        val a = repo.savePlace(draft("Клініка А", mapOf(proc to 1250L), green))
        val b = repo.savePlace(draft("Клініка Б", mapOf(proc to 2000L), blue))

        val places = repo.places.first().associateBy { it.place.id }
        assertEquals(mapOf(proc to 1250L), places.getValue(a).ratesMap())
        assertEquals(mapOf(proc to 2000L), places.getValue(b).ratesMap())
        assertEquals(listOf(Procedure(proc, "Чистка")), repo.procedures.first())
    }

    @Test
    fun `data survives closing and reopening the database file`() = catalogTest { repo, store, path ->
        val proc = repo.createProcedure("Огляд")
        val a = repo.savePlace(draft("Клініка А", mapOf(proc to 1250L), address = "вул. Лісова, 5"))
        val b = repo.savePlace(draft("Клініка Б", mapOf(proc to 2000L), blue))
        store.close()

        val reopened = DataStore(path)
        try {
            val repo2 = CatalogRepository(reopened)
            assertEquals(listOf(Procedure(proc, "Огляд")), repo2.procedures.first())
            val places = repo2.places.first()
            assertEquals(listOf(a, b), places.map { it.place.id })
            assertEquals("вул. Лісова, 5", places[0].place.address)
            assertEquals(blue, places[1].place.color)
            assertEquals(mapOf(proc to 1250L), places[0].ratesMap())
            assertEquals(mapOf(proc to 2000L), places[1].ratesMap())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `procedure count is limited like the snapshot`() = catalogTest { repo, store, _ ->
        store.write { db ->
            repeat(CatalogLimits.MAX_PROCEDURES) { i ->
                db.execute("INSERT INTO procedure (name) VALUES (?)") { bindText(1, "П$i") }
            }
        }
        assertFailsWith<LimitExceeded> { repo.createProcedure("Зайва") }
        assertEquals("name", assertFailsWith<ValidationError> { repo.createProcedure("а".repeat(CatalogLimits.MAX_NAME_LENGTH + 1)) }.field)
    }

    @Test
    fun `blank names are rejected and nothing is stored`() = catalogTest { repo, _, _ ->
        assertEquals("name", assertFailsWith<ValidationError> { repo.createProcedure("   ") }.field)
        assertEquals("name", assertFailsWith<ValidationError> { repo.savePlace(draft("")) }.field)
        val id = repo.createProcedure("Огляд")
        assertFailsWith<ValidationError> { repo.renameProcedure(id, " ") }
        assertEquals(listOf("Огляд"), repo.procedures.first().map { it.name })
        assertTrue(repo.places.first().isEmpty())
    }

    @Test
    fun `two places may share a colour`() = catalogTest { repo, _, _ ->
        repo.savePlace(draft("Клініка А", color = green))
        repo.savePlace(draft("Клініка Б", color = green))
        assertEquals(listOf(green, green), repo.places.first().map { it.place.color })
    }

    @Test
    fun `negative rate is rejected and nothing is stored`() = catalogTest { repo, store, _ ->
        val proc = repo.createProcedure("Огляд")
        val e = assertFailsWith<ValidationError> { repo.savePlace(draft("Клініка", mapOf(proc to -1L))) }
        assertEquals("rate.$proc", e.field)
        assertEquals(0L, store.read { it.count("SELECT COUNT(*) FROM place") })
    }

    @Test
    fun `place with history is archived while one without history is deleted`() = catalogTest { repo, store, _ ->
        val proc = repo.createProcedure("Огляд")
        val used = repo.savePlace(draft("З історією", mapOf(proc to 500L)))
        val free = repo.savePlace(draft("Без історії", mapOf(proc to 700L)))
        store.addHistory(used, proc)

        assertEquals(RemovalResult.Archived, repo.removePlace(used))
        assertEquals(RemovalResult.Deleted, repo.removePlace(free))

        assertTrue(repo.places.first().isEmpty())
        assertEquals(1L, store.read { it.count("SELECT COUNT(*) FROM place WHERE id = $used AND archived = 1") })
        assertEquals(0L, store.read { it.count("SELECT COUNT(*) FROM place WHERE id = $free") })
        assertEquals(0L, store.read { it.count("SELECT COUNT(*) FROM place_rate WHERE placeId = $free") })
        // History and rates of the archived place are kept.
        assertEquals(1L, store.read { it.count("SELECT COUNT(*) FROM work_entry WHERE placeId = $used") })
        assertEquals(1L, store.read { it.count("SELECT COUNT(*) FROM place_rate WHERE placeId = $used") })
    }

    @Test
    fun `procedure with history is archived while one without history is deleted with its rates`() =
        catalogTest { repo, store, _ ->
            val used = repo.createProcedure("Використана")
            val free = repo.createProcedure("Вільна")
            val place = repo.savePlace(draft("Клініка", mapOf(used to 500L, free to 700L)))
            store.addHistory(place, used)

            assertEquals(RemovalResult.Archived, repo.removeProcedure(used))
            assertEquals(RemovalResult.Deleted, repo.removeProcedure(free))

            assertTrue(repo.procedures.first().isEmpty())
            assertEquals(1L, store.read { it.count("SELECT COUNT(*) FROM procedure WHERE id = $used AND archived = 1") })
            assertEquals(0L, store.read { it.count("SELECT COUNT(*) FROM procedure WHERE id = $free") })
            assertEquals(0L, store.read { it.count("SELECT COUNT(*) FROM place_rate WHERE procedureId = $free") })
            // The archived procedure's rate is kept but an active place does not show it.
            assertEquals(1L, store.read { it.count("SELECT COUNT(*) FROM place_rate WHERE procedureId = $used") })
            assertEquals(emptyMap(), repo.places.first().single().ratesMap())
        }

    @Test
    fun `saving a place replaces its rates in one transaction`() = catalogTest { repo, store, _ ->
        val p1 = repo.createProcedure("Огляд")
        val p2 = repo.createProcedure("Чистка")
        val p3 = repo.createProcedure("Пломба")
        val place = repo.savePlace(draft("Клініка", mapOf(p1 to 100L, p2 to 200L)))

        repo.savePlace(draft("Клініка", mapOf(p2 to 250L, p3 to 300L), id = place))
        assertEquals(mapOf(p2 to 250L, p3 to 300L), repo.places.first().single().ratesMap())

        val revision = store.revision.value
        // Unknown procedure.
        assertFailsWith<ValidationError> {
            repo.savePlace(draft("Нова назва", mapOf(p2 to 1L, 9999L to 5L), id = place))
        }
        // Negative price.
        assertFailsWith<ValidationError> {
            repo.savePlace(draft("Нова назва", mapOf(p2 to 1L, p3 to -5L), id = place))
        }
        assertEquals(revision, store.revision.value)
        val after = repo.places.first().single()
        assertEquals("Клініка", after.place.name)
        assertEquals(mapOf(p2 to 250L, p3 to 300L), after.ratesMap())
    }

    @Test
    fun `editing an existing place keeps its id`() = catalogTest { repo, store, _ ->
        val proc = repo.createProcedure("Огляд")
        val id = repo.savePlace(draft("Стара", mapOf(proc to 100L), green))
        val same = repo.savePlace(draft(" Нова ", mapOf(proc to 900L), blue, id = id, address = "Київ"))
        assertEquals(id, same)

        val place = repo.places.first().single()
        assertEquals(id, place.place.id)
        assertEquals("Нова", place.place.name)
        assertEquals("Київ", place.place.address)
        assertEquals(blue, place.place.color)
        assertEquals(mapOf(proc to 900L), place.ratesMap())
        assertEquals(1L, store.read { it.count("SELECT COUNT(*) FROM place") })
        assertFailsWith<ValidationError> { repo.savePlace(draft("Привид", id = 12345L)) }
    }

    @Test
    fun `procedures can be renamed and lists are sorted case-insensitively`() = catalogTest { repo, _, _ ->
        val b = repo.createProcedure("бета")
        repo.createProcedure("Альфа")
        repo.createProcedure("Гамма")
        assertEquals(listOf("Альфа", "бета", "Гамма"), repo.procedures.first().map { it.name })
        repo.renameProcedure(b, " Яблуко ")
        assertEquals(listOf("Альфа", "Гамма", "Яблуко"), repo.procedures.first().map { it.name })

        repo.savePlace(draft("зебра"))
        repo.savePlace(draft("Акація"))
        assertEquals(listOf("Акація", "зебра"), repo.places.first().map { it.place.name })
    }

    @Test
    fun `flows emit again after a write`() = catalogTest { repo, _, _ ->
        val emissions = Channel<List<Procedure>>(Channel.UNLIMITED)
        val job = launch { repo.procedures.collect { emissions.send(it) } }
        try {
            assertEquals(emptyList(), emissions.receive())
            val id = repo.createProcedure("Огляд")
            assertEquals(listOf(Procedure(id, "Огляд")), emissions.receive())
            repo.renameProcedure(id, "Огляд повний")
            assertEquals("Огляд повний", emissions.receive().single().name)
        } finally {
            job.cancel()
        }

        val placeEmissions = Channel<List<PlaceWithRates>>(Channel.UNLIMITED)
        val placesJob = launch { repo.places.collect { placeEmissions.send(it) } }
        try {
            assertTrue(placeEmissions.receive().isEmpty())
            val id = repo.savePlace(draft("Клініка"))
            assertEquals(id, placeEmissions.receive().single().place.id)
            assertNotEquals(0L, id)
        } finally {
            placesJob.cancel()
        }
    }

    /** Simulates a restore from a backup: the whole dataset is replaced and the same IDs now mean other rows. */
    private suspend fun DataStore.restoreOtherSet() = restore { db ->
        db.execSQL("DELETE FROM work_line")
        db.execSQL("DELETE FROM work_entry")
        db.execSQL("DELETE FROM place_rate")
        db.execSQL("DELETE FROM place")
        db.execSQL("DELETE FROM procedure")
        db.execSQL("INSERT INTO procedure (id, name) VALUES (1, 'Відновлена 1'), (2, 'Відновлена 2')")
        db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'Відновлене місце', 4280391411)")
        db.execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 1, 700)")
    }

    private fun SQLiteConnection.wholeSet(): List<String> =
        query("SELECT 'proc ' || id || ' ' || name || ' ' || archived FROM procedure ORDER BY id") { it.getText(0) } +
            query("SELECT 'place ' || id || ' ' || name || ' ' || archived FROM place ORDER BY id") { it.getText(0) } +
            query(
                "SELECT 'rate ' || placeId || ' ' || procedureId || ' ' || amountMinor FROM place_rate " +
                    "ORDER BY placeId, procedureId",
            ) { it.getText(0) }

    @Test
    fun `snapshot reads catalogs together with the generation and follows a restore`() = catalogTest { repo, store, _ ->
        val proc = repo.createProcedure("Огляд")
        val place = repo.savePlace(draft("Клініка", mapOf(proc to 500L)))
        val before = repo.snapshots.first()
        assertEquals(1L, before.generation)
        assertEquals(listOf(Procedure(proc, "Огляд")), before.procedures)
        assertEquals(listOf(place), before.places.map { it.place.id })

        val emissions = Channel<CatalogSnapshot>(Channel.UNLIMITED)
        val job = launch { repo.snapshots.collect { emissions.send(it) } }
        try {
            assertEquals(before, withTimeout(10_000) { emissions.receive() })
            store.restoreOtherSet()
            val after = withTimeout(10_000) { emissions.receive() }
            assertEquals(2L, after.generation)
            assertEquals(listOf("Відновлена 1", "Відновлена 2"), after.procedures.map { it.name })
            assertEquals(listOf("Відновлене місце"), after.places.map { it.place.name })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `catalog edits opened before a committed restore are rejected and keep the restored set`() =
        catalogTest { repo, store, _ ->
            val p1 = repo.createProcedure("Стара 1")
            val p2 = repo.createProcedure("Стара 2")
            val place = repo.savePlace(draft("Стара клініка", mapOf(p1 to 100L, p2 to 200L)))
            val opened = repo.snapshots.first().generation
            assertEquals(1L, opened)

            store.restoreOtherSet()
            val restored = store.read { it.wholeSet() }
            val revision = store.revision.value

            // Editors opened before the restore save after its commit.
            assertFailsWith<StaleGeneration> { repo.createProcedure("Нова", opened) }
            assertFailsWith<StaleGeneration> { repo.renameProcedure(p1, "Перейменована", opened) }
            assertFailsWith<StaleGeneration> { repo.removeProcedure(p2, opened) }
            assertFailsWith<StaleGeneration> { repo.savePlace(draft("Змінена", mapOf(p1 to 900L), id = place), opened) }
            assertFailsWith<StaleGeneration> { repo.savePlace(draft("Нове місце", mapOf(p1 to 900L)), opened) }
            assertFailsWith<StaleGeneration> { repo.removePlace(place, opened) }

            assertEquals(restored, store.read { it.wholeSet() })
            assertEquals(revision, store.revision.value)
            assertEquals(2L, store.currentGeneration())

            // A reopened editor works with the generation of the restored dataset.
            val reopened = repo.snapshots.first().generation
            assertEquals(2L, reopened)
            repo.renameProcedure(1L, "Після відновлення", reopened)
            assertEquals(RemovalResult.Deleted, repo.removeProcedure(2L, reopened))
            val created = repo.createProcedure("Нова", reopened)
            assertEquals(1L, repo.savePlace(draft("Відновлене місце", mapOf(created to 300L), id = 1L), reopened))
            assertEquals(listOf("Нова", "Після відновлення"), repo.procedures.first().map { it.name })
            assertEquals(mapOf(created to 300L), repo.places.first().single().ratesMap())
        }

    @Test
    fun `catalog edit queued behind a restore is rejected as stale`() {
        val gate = GatedDispatcher()
        catalogTest(gate) { repo, store, _ ->
            val proc = repo.createProcedure("Стара")
            val place = repo.savePlace(draft("Стара клініка", mapOf(proc to 100L)))
            val opened = repo.snapshots.first().generation

            // Both saves have been called but have not reached the store yet.
            gate.holding = true
            val rename = async(Dispatchers.Default) { runCatching { repo.renameProcedure(proc, "Пізня", opened) } }
            val save = async(Dispatchers.Default) {
                runCatching { repo.savePlace(draft("Пізня клініка", mapOf(proc to 999L), id = place), opened) }
            }
            withTimeout(5_000) { while (gate.heldCount() < 2) delay(5) }
            gate.holding = false

            store.restoreOtherSet()
            val restored = store.read { it.wholeSet() }
            val revision = store.revision.value
            gate.releaseHeld()

            assertTrue(rename.await().exceptionOrNull() is StaleGeneration, "${rename.await()}")
            assertTrue(save.await().exceptionOrNull() is StaleGeneration, "${save.await()}")
            assertEquals(restored, store.read { it.wholeSet() })
            assertEquals(revision, store.revision.value)
        }
    }

    @Test
    fun `catalog edits without an opened generation still work and matching generation passes`() =
        catalogTest { repo, store, _ ->
            val generation = repo.snapshots.first().generation
            val proc = repo.createProcedure("Огляд", generation)
            repo.renameProcedure(proc, "Огляд повний", generation)
            val place = repo.savePlace(draft("Клініка", mapOf(proc to 100L)), generation)
            assertEquals(RemovalResult.Deleted, repo.removePlace(place, generation))
            assertEquals(RemovalResult.Deleted, repo.removeProcedure(proc))
            assertEquals(generation, store.currentGeneration())
        }
}
