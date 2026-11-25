package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.db.query
import app.servicecalendar.db.readDataGeneration
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.StorageBusy
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/** The restore mode of the shared data store. */
@OptIn(ExperimentalAtomicApi::class)
class DataStoreRestoreTest {
    private class Boom : RuntimeException("boom")

    private val nov1 = LocalDate(2026, 11, 1)

    private fun storeTest(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend CoroutineScope.(store: DataStore, path: String) -> Unit,
    ) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path, dispatcher)
                try {
                    withTimeout(60_000) { block(store, path) }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    private fun SQLiteConnection.names(): List<String> =
        query("SELECT name FROM procedure ORDER BY id") { it.getText(0) }

    /** The dataset before a restore: place 1 and procedure 1 "Стара" with a rate of 12.50. */
    private suspend fun DataStore.seedOld() = write { db ->
        db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'Клініка', 4293284096)")
        db.execSQL("INSERT INTO procedure (id, name) VALUES (1, 'Стара')")
        db.execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 1, 1250)")
    }

    /** Replaces the whole dataset with procedures 10 and 11 and place 5 with rates. */
    private fun SQLiteConnection.replaceWithNew() {
        execSQL("DELETE FROM work_line")
        execSQL("DELETE FROM work_entry")
        execSQL("DELETE FROM place_rate")
        execSQL("DELETE FROM place")
        execSQL("DELETE FROM procedure")
        execSQL("INSERT INTO procedure (id, name) VALUES (10, 'Нова'), (11, 'Друга')")
        execSQL("INSERT INTO place (id, name, color) VALUES (5, 'Відновлене', 4280391411)")
        execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (5, 10, 700), (5, 11, 900)")
    }

    /**
     * A store block that signals [entered] and waits for [release]. It gives up after 10 s so that a
     * test failing before it releases the block cannot hang the run.
     */
    private fun holdInsideBlock(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
        entered.complete(Unit)
        runBlocking { withTimeout(10_000) { release.await() } }
    }

    @Test
    fun `restore replaces the dataset and bumps the generation in one transaction`() = storeTest { store, _ ->
        store.seedOld()
        val revisionBefore = store.revision.value
        assertEquals(1L, store.currentGeneration())

        val seenInside = store.restore { db ->
            assertTrue(db.inTransaction())
            db.replaceWithNew()
            // Inside the block the generation is still the old one: the bump is part of the same transaction.
            db.readDataGeneration()
        }

        assertEquals(1L, seenInside)
        assertEquals(2L, store.currentGeneration())
        assertEquals(2L, store.dataGeneration.value)
        assertEquals(revisionBefore + 1, store.revision.value)
        assertFalse(store.isRestoring.value)
        assertEquals(listOf("Нова", "Друга"), store.read { it.names() })
    }

    @Test
    fun `failed restore keeps the data and the generation`() = storeTest { store, _ ->
        store.seedOld()
        val revisionBefore = store.revision.value

        assertFailsWith<Boom> {
            store.restore { db ->
                db.replaceWithNew()
                throw Boom()
            }
        }
        // A constraint violation midway through the inserts also rolls everything back.
        assertFailsWith<Exception> {
            store.restore { db ->
                db.replaceWithNew()
                db.execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (5, 999, 1)")
            }
        }
        // A block that removes the generation row cannot commit.
        assertFailsWith<Exception> {
            store.restore { db ->
                db.replaceWithNew()
                db.execSQL("DELETE FROM device_state")
            }
        }

        assertEquals(1L, store.currentGeneration())
        assertEquals(1L, store.dataGeneration.value)
        assertEquals(revisionBefore, store.revision.value)
        assertFalse(store.isRestoring.value)
        assertEquals(listOf("Стара"), store.read { it.names() })
        // The store stays usable and the next restore succeeds.
        store.restore { it.replaceWithNew() }
        assertEquals(2L, store.currentGeneration())
    }

    @Test
    fun `editor save with the generation opened before restore is rejected`() = storeTest { store, _ ->
        store.seedOld()
        val entries = WorkEntryRepository(store)
        val editor = entries.loadEditor(nov1, 1)
        assertEquals(1L, editor.generation)

        store.restore { db ->
            db.replaceWithNew()
            db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'Інше місце', 4280391411)")
            db.execSQL("INSERT INTO procedure (id, name) VALUES (1, 'Інша')")
            db.execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 1, 5)")
        }
        val revisionAfterRestore = store.revision.value

        // A late save from the old editor: ID 1 now refers to different rows.
        assertFailsWith<StaleGeneration> { entries.save(nov1, 1, mapOf(1L to 3), editor.generation) }
        assertEquals(0L, store.read { db -> db.query("SELECT COUNT(*) FROM work_entry") { it.getLong(0) }.single() })
        assertEquals(revisionAfterRestore, store.revision.value)

        // A reopened editor saves on top of the restored dataset.
        val reopened = entries.loadEditor(nov1, 1)
        assertEquals(2L, reopened.generation)
        entries.save(nov1, 1, mapOf(1L to 3), reopened.generation)
    }

    @Test
    fun `writes requested during restore fail fast and reads wait for the whole restored set`() =
        storeTest { store, _ ->
            store.seedOld()
            val entries = WorkEntryRepository(store)
            val editor = entries.loadEditor(nov1, 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val restoring = async(Dispatchers.Default) {
                store.restore { db ->
                    db.replaceWithNew()
                    holdInsideBlock(entered, release)
                }
            }
            entered.await()
            assertTrue(store.isRestoring.value)

            // New writes are rejected immediately while the restore block still holds the store.
            assertFailsWith<StorageBusy> { store.write { it.execSQL("INSERT INTO procedure (name) VALUES ('Пізно')") } }
            assertFailsWith<StorageBusy> { entries.save(nov1, 1, mapOf(1L to 2), editor.generation) }
            assertFailsWith<StorageBusy> { store.restore { it.replaceWithNew() } }

            // Reads (including the one for a backup snapshot) wait and see only the whole restored dataset.
            val reading = async(Dispatchers.Default) { store.read { it.names() } }
            delay(100)
            assertFalse(reading.isCompleted)

            release.complete(Unit)
            restoring.await()
            assertEquals(listOf("Нова", "Друга"), reading.await())
            assertFalse(store.isRestoring.value)
            assertEquals(2L, store.currentGeneration())
        }

    @Test
    fun `already started write finishes before the restore replaces the set`() = storeTest { store, _ ->
        store.seedOld()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val writing = async(Dispatchers.Default) {
            store.write { db ->
                db.execSQL("INSERT INTO procedure (id, name) VALUES (2, 'Розпочата')")
                holdInsideBlock(entered, release)
                "записано"
            }
        }
        entered.await()
        val restoring = async(Dispatchers.Default) { store.restore { db -> db.names().also { db.replaceWithNew() } } }
        // Restore mode switches on immediately, but the restore itself waits for the write in progress.
        withTimeout(5_000) { while (!store.isRestoring.value) delay(5) }
        delay(100)
        assertFalse(restoring.isCompleted)

        release.complete(Unit)
        assertEquals("записано", writing.await())
        // The restore saw the committed write and replaced it together with the whole dataset.
        assertEquals(listOf("Стара", "Розпочата"), restoring.await())
        assertEquals(listOf("Нова", "Друга"), store.read { it.names() })
    }

    @Test
    fun `write queued behind a committed restore is rejected as stale`() {
        val gate = GatedDispatcher()
        storeTest(gate) { store, _ ->
            store.seedOld()
            val entries = WorkEntryRepository(store)
            val editor = entries.loadEditor(nov1, 1)

            // Both calls have passed the mode check but have not reached the Mutex yet.
            gate.holding = true
            val plainWrite = async(Dispatchers.Default) {
                runCatching { store.write { it.execSQL("INSERT INTO procedure (name) VALUES ('Затримана')") } }
            }
            val editorSave = async(Dispatchers.Default) {
                runCatching { entries.save(nov1, 1, mapOf(1L to 2), editor.generation) }
            }
            withTimeout(5_000) { while (gate.heldCount() < 2) delay(5) }
            gate.holding = false

            store.restore { it.replaceWithNew() }
            val revisionAfterRestore = store.revision.value
            gate.releaseHeld()

            assertTrue(plainWrite.await().exceptionOrNull() is StaleGeneration, "${plainWrite.await()}")
            assertTrue(editorSave.await().exceptionOrNull() is StaleGeneration, "${editorSave.await()}")
            assertEquals(listOf("Нова", "Друга"), store.read { it.names() })
            assertEquals(0L, store.read { db -> db.query("SELECT COUNT(*) FROM work_entry") { it.getLong(0) }.single() })
            assertEquals(revisionAfterRestore, store.revision.value)

            // A new write after the restore goes through.
            store.write { it.execSQL("INSERT INTO procedure (name) VALUES ('Свіжа')") }
            assertEquals(listOf("Нова", "Друга", "Свіжа"), store.read { it.names() })
        }
    }

    @Test
    fun `parallel saves and reads during restores never see a half replaced set`() = storeTest { store, _ ->
        // Invariant of every whole dataset: N procedures with the same name prefix and equal rates.
        val size = 5
        fun SQLiteConnection.fillSet(tag: String) {
            execSQL("DELETE FROM place_rate")
            execSQL("DELETE FROM place")
            execSQL("DELETE FROM procedure")
            execSQL("INSERT INTO place (id, name, color) VALUES (1, 'Місце $tag', 4293284096)")
            for (i in 1..size) {
                execSQL("INSERT INTO procedure (id, name) VALUES ($i, '$tag-$i')")
                execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, $i, 0)")
            }
        }
        fun SQLiteConnection.snapshot(): Pair<List<String>, List<Long>> =
            names() to query("SELECT amountMinor FROM place_rate ORDER BY procedureId") { it.getLong(0) }

        store.write { it.fillSet("v0") }
        val insideBlocks = AtomicInt(0)
        val overlaps = AtomicInt(0)
        fun <T> exclusive(action: () -> T): T {
            if (insideBlocks.incrementAndFetch() != 1) overlaps.incrementAndFetch()
            try {
                return action()
            } finally {
                insideBlocks.decrementAndFetch()
            }
        }

        val restores = (1..20).map { v ->
            async(Dispatchers.Default) {
                runCatching { store.restore { db -> exclusive { db.fillSet("v$v") } } }
            }
        }
        val writes = (1..200).map {
            async(Dispatchers.Default) {
                runCatching {
                    store.write { db ->
                        exclusive { db.execSQL("UPDATE place_rate SET amountMinor = amountMinor + 1") }
                    }
                }
            }
        }
        val reads = (1..300).map {
            async(Dispatchers.Default) { store.read { db -> exclusive { db.snapshot() } } }
        }

        val restoreResults = restores.awaitAll()
        val writeResults = writes.awaitAll()
        val seen = reads.awaitAll()

        assertEquals(0, overlaps.load(), "blocks ran concurrently")
        restoreResults.forEach { r -> assertTrue(r.isSuccess || r.exceptionOrNull() is StorageBusy, "$r") }
        writeResults.forEach { r ->
            val e = r.exceptionOrNull()
            assertTrue(e == null || e is StorageBusy || e is StaleGeneration, "$r")
        }
        seen.forEach { (names, rates) ->
            assertEquals(size, names.size, "incomplete set: $names")
            val tag = names.first().substringBefore('-')
            assertTrue(names.all { it.startsWith("$tag-") }, "mixed set: $names")
            assertEquals(1, rates.toSet().size, "partial write: $rates")
        }
        val committedRestores = restoreResults.count { it.isSuccess }.toLong()
        assertTrue(committedRestores >= 1)
        assertEquals(1L + committedRestores, store.currentGeneration())
        assertEquals(store.currentGeneration(), store.dataGeneration.value)
        assertFalse(store.isRestoring.value)
    }

    @Test
    fun `generation flow follows opening and restores and survives reopening`() = storeTest { store, path ->
        assertNull(store.dataGeneration.value)
        store.seedOld()
        assertEquals(1L, store.dataGeneration.value)

        store.restore { it.replaceWithNew() }
        store.restore { it.replaceWithNew() }
        assertEquals(3L, store.dataGeneration.value)
        store.close()

        val reopened = DataStore(path)
        try {
            assertNull(reopened.dataGeneration.value)
            assertEquals(listOf("Нова", "Друга"), reopened.read { it.names() })
            assertEquals(3L, reopened.dataGeneration.value)
            assertEquals(3L, reopened.currentGeneration())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `restore is rejected when nested or after close and the mode is released`() = storeTest { store, _ ->
        assertFailsWith<NestedDataStoreAccessException> {
            store.write { runBlocking { store.restore { 1 } } }
        }
        assertFailsWith<NestedDataStoreAccessException> {
            store.restore { runBlocking { store.read { 1 } } }
        }
        assertFalse(store.isRestoring.value)
        assertEquals(1L, store.currentGeneration())

        store.close()
        assertFailsWith<IllegalStateException> { store.restore { 1 } }
        assertFalse(store.isRestoring.value)
    }

    @Test
    fun `restore cancelled while waiting changes nothing`() = storeTest { store, _ ->
        store.seedOld()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writing = launch(Dispatchers.Default) { store.write { holdInsideBlock(entered, release) } }
        entered.await()

        val restoring = launch(Dispatchers.Default) { store.restore { it.replaceWithNew() } }
        withTimeout(5_000) { while (!store.isRestoring.value) delay(5) }
        restoring.cancelAndJoin()
        assertFalse(store.isRestoring.value)

        release.complete(Unit)
        writing.join()
        assertEquals(listOf("Стара"), store.read { it.names() })
        assertEquals(1L, store.currentGeneration())
    }
}
