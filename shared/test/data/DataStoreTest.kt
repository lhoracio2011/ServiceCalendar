package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.db.UnsupportedSchemaVersionException
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.execute
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.db.openDatabase
import app.servicecalendar.db.query
import app.servicecalendar.db.transaction
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The shared data store: a single connection guarded by one `Mutex`. */
@OptIn(ExperimentalAtomicApi::class)
class DataStoreTest {
    private class Boom : RuntimeException("boom")

    /** Runs [block] against a fresh database file per test. */
    private fun storeTest(block: suspend CoroutineScope.(store: DataStore, path: String) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path)
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

    private fun SQLiteConnection.longValue(sql: String): Long =
        query(sql, mapper = { it.getLong(0) }).single()

    private fun SQLiteConnection.procedureCount(): Long = longValue("SELECT COUNT(*) FROM procedure")

    private fun SQLiteConnection.insertProcedure(name: String) =
        execute("INSERT INTO procedure (name) VALUES (?)") { bindText(1, name) }

    /** Place 1 and procedures 1 and 2 with zero rates; the invariant under test is that both rates stay equal. */
    private suspend fun DataStore.seedTwoRates() = write { db ->
        db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'Клініка', 4293284096)")
        db.execSQL("INSERT INTO procedure (id, name) VALUES (1, 'Огляд'), (2, 'Чистка')")
        db.execSQL("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 1, 0), (1, 2, 0)")
    }

    private fun SQLiteConnection.rates(): Pair<Long, Long> =
        query("SELECT amountMinor FROM place_rate WHERE placeId = 1 ORDER BY procedureId") { it.getLong(0) }
            .let { it[0] to it[1] }

    @Test
    fun `schema is ready on first access and generation comes from device state`() =
        storeTest { store, path ->
            assertEquals(1L, store.currentGeneration())
            assertEquals(1L, store.read { it.longValue("PRAGMA foreign_keys") })
            store.write { it.execSQL("UPDATE device_state SET dataGeneration = 7") }
            assertEquals(7L, store.currentGeneration())

            store.close()
            val reopened = DataStore(path)
            try {
                assertEquals(7L, reopened.currentGeneration())
            } finally {
                reopened.close()
            }
        }

    @Test
    fun `revision grows only after a committed write`() = storeTest { store, _ ->
        assertEquals(0L, store.revision.value)
        assertEquals(0L, store.read { it.procedureCount() })
        assertEquals(0L, store.revision.value)

        val result = store.write { db ->
            db.insertProcedure("Огляд")
            "збережено"
        }
        assertEquals("збережено", result)
        assertEquals(1L, store.revision.value)

        assertFailsWith<Boom> {
            store.write { db ->
                db.insertProcedure("Втрачено")
                throw Boom()
            }
        }
        assertFailsWith<Exception> { store.write { db -> db.insertProcedure(" ") } }
        assertEquals(1L, store.revision.value)
        assertEquals(1L, store.read { it.procedureCount() })
    }

    @Test
    fun `nested transaction inside write rolls back the whole write`() = storeTest { store, _ ->
        assertFailsWith<Exception> {
            store.write { db ->
                db.insertProcedure("Зовнішня")
                db.transaction { db.insertProcedure("Внутрішня") }
            }
        }
        assertEquals(0L, store.read { it.procedureCount() })
        assertEquals(0L, store.revision.value)
        store.write { it.insertProcedure("Після") }
        assertEquals(1L, store.read { it.procedureCount() })
    }

    @Test
    fun `hundreds of parallel reads and writes see only whole transactions`() = storeTest { store, _ ->
        store.seedTwoRates()
        val seededRevision = store.revision.value
        val writes = 300
        val reads = 500
        val insideBlocks = AtomicInt(0)
        val overlaps = AtomicInt(0)

        fun enter() {
            if (insideBlocks.incrementAndFetch() != 1) overlaps.incrementAndFetch()
        }

        fun exit() {
            insideBlocks.decrementAndFetch()
        }

        val writers = (1..writes).map { i ->
            async(Dispatchers.Default) {
                runCatching {
                    store.write { db ->
                        enter()
                        try {
                            db.execSQL("UPDATE place_rate SET amountMinor = amountMinor + 1 WHERE procedureId = 1")
                            // Every tenth write fails midway; its half-applied change must never become visible.
                            if (i % 10 == 0) throw Boom()
                            db.execSQL("UPDATE place_rate SET amountMinor = amountMinor + 1 WHERE procedureId = 2")
                        } finally {
                            exit()
                        }
                    }
                }.isSuccess
            }
        }
        val readers = (1..reads).map {
            async(Dispatchers.Default) {
                store.read { db ->
                    enter()
                    try {
                        db.rates()
                    } finally {
                        exit()
                    }
                }
            }
        }

        val committed = writers.awaitAll().count { it }
        val seen = readers.awaitAll()

        assertEquals(writes - writes / 10, committed)
        assertEquals(0, overlaps.load(), "blocks ran concurrently")
        seen.forEach { (first, second) -> assertEquals(first, second, "partial write visible to a read") }
        assertEquals(committed.toLong() to committed.toLong(), store.read { it.rates() })
        assertEquals(seededRevision + committed, store.revision.value)
    }

    @Test
    fun `write from inside read is rejected and the store stays usable`() = storeTest { store, _ ->
        assertFailsWith<Exception> { store.read { it.insertProcedure("Таємно") } }
        assertEquals(0L, store.read { it.procedureCount() })
        assertEquals(0L, store.revision.value)
        store.write { it.insertProcedure("Явно") }
        assertEquals(1L, store.read { it.procedureCount() })
    }

    @Test
    fun `nested access from a block fails fast instead of deadlocking`() = storeTest { store, _ ->
        assertFailsWith<NestedDataStoreAccessException> {
            store.write { db ->
                db.insertProcedure("Зовнішня")
                runBlocking { store.read { it.procedureCount() } }
            }
        }
        assertFailsWith<NestedDataStoreAccessException> {
            store.read { runBlocking { store.write { it.insertProcedure("Вкладена") } } }
        }
        assertFailsWith<NestedDataStoreAccessException> {
            store.read { runBlocking { store.currentGeneration() } }
        }
        assertEquals(0L, store.read { it.procedureCount() })
        assertEquals(0L, store.revision.value)
        store.write { it.insertProcedure("Після") }
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun `another store may be used from inside a block`() = storeTest { store, _ ->
        val otherPath = newTestDatabasePath()
        val other = DataStore(otherPath)
        try {
            val count = store.read { runBlocking { other.read { it.procedureCount() } } }
            assertEquals(0L, count)
        } finally {
            other.close()
            deleteTestDatabase(otherPath)
        }
    }

    @Test
    fun `connection given to a block cannot be closed or kept`() = storeTest { store, _ ->
        assertFailsWith<IllegalStateException> { store.read { it.close() } }
        assertFailsWith<IllegalStateException> { store.write { db -> db.use { } } }

        val leaked = store.read { it }
        assertFailsWith<IllegalStateException> { leaked.prepare("SELECT 1") }
        assertFailsWith<IllegalStateException> { leaked.close() }

        store.write { it.insertProcedure("Працює") }
        assertEquals(1L, store.read { it.procedureCount() })
    }

    @Test
    fun `closed store rejects access and close is idempotent`() = storeTest { store, _ ->
        store.write { it.insertProcedure("Огляд") }
        store.close()
        store.close()
        assertFailsWith<IllegalStateException> { store.read { it.procedureCount() } }
        assertFailsWith<IllegalStateException> { store.write { it.insertProcedure("Пізно") } }
    }

    @Test
    fun `committed data survives reopening the store`() = storeTest { store, path ->
        store.write { it.insertProcedure("Огляд") }
        store.close()
        val reopened = DataStore(path)
        try {
            assertEquals(0L, reopened.revision.value)
            assertEquals(1L, reopened.read { it.procedureCount() })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `database from a newer app version is reported on access`() {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            openDatabase(path).use { it.execSQL("PRAGMA user_version = 99") }
            runBlocking {
                val store = DataStore(path)
                try {
                    assertFailsWith<UnsupportedSchemaVersionException> { store.read { 1 } }
                    // The failure leaves no half-open connection: a retry reports the same cause.
                    assertFailsWith<UnsupportedSchemaVersionException> { store.write { 1 } }
                    assertEquals(0L, store.revision.value)
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }
}
