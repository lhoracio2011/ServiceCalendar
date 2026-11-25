package app.servicecalendar.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.data.DataStore
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.db.query
import app.servicecalendar.db.readDataGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Backup guard state stored in `device_state` through the shared data store. */
class BackupStateStoreTest {
    private class Boom : RuntimeException("boom")

    private val accountA = BackupBinding("account-a", 1)
    private val accountB = BackupBinding("account-b", 1)
    private val noon = Instant.parse("2026-11-10T12:00:00Z")

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

    /** Simulates a new process: a separate store over the same file. */
    private suspend fun <T> reopened(path: String, block: suspend (BackupStateStore) -> T): T {
        val store = DataStore(path)
        try {
            return block(BackupStateStore(store))
        } finally {
            store.close()
        }
    }

    private fun SQLiteConnection.longValue(sql: String): Long =
        query(sql, mapper = { it.getLong(0) }).single()

    @Test
    fun `fresh database has the initial state`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        assertEquals(BackupState.INITIAL, states.load())
        assertEquals(DeviceBackupState(BackupState.INITIAL, dataGeneration = 1), states.loadWithGeneration())
    }

    @Test
    fun `every field survives a round trip`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        val full = BackupState(
            accountId = "account-a",
            searchState = BackupSearchState.FOUND_COPY,
            choice = BackupChoice.REPLACE_CONFIRMED,
            driveFileId = "drive-file-1",
            lastSuccessfulBackupAt = Instant.parse("2026-11-10T08:30:15.250Z"),
            boundGeneration = 1,
            remoteRevision = "rev-42",
        )
        states.save(full)
        assertEquals(full, states.load())
        assertTrue(states.load().dailyBackupsAllowed)

        BackupSearchState.entries.forEach { search ->
            val state = when (search) {
                BackupSearchState.FOUND_COPY -> BackupGuard.postpone(full.copy(choice = BackupChoice.NONE), accountA)
                BackupSearchState.FOUND_NONE -> full.copy(searchState = search, choice = BackupChoice.NONE)
                else -> BackupState.freshFor(accountA).copy(searchState = search)
            }
            states.save(state)
            assertEquals(state, states.load(), "state $search")
        }
    }

    @Test
    fun `backup state does not touch the data generation or data tables`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        store.write { it.execSQL("UPDATE device_state SET dataGeneration = 5") }
        states.save(BackupGuard.onSearchStarted(BackupState.INITIAL, BackupBinding("account-a", 5)))
        assertEquals(5L, store.currentGeneration())
        assertEquals(0L, store.read { it.longValue("SELECT COUNT(*) FROM procedure") })
        assertEquals(1L, store.read { it.longValue("SELECT COUNT(*) FROM device_state") })
    }

    @Test
    fun `postponed choice survives an app restart and keeps the guard on`() = storeTest { store, path ->
        val states = BackupStateStore(store)
        states.update { state, generation ->
            val binding = BackupBinding("account-a", generation)
            val found = BackupGuard.onSearchFoundCopy(BackupGuard.onSearchStarted(state, binding), binding, "remote")
            BackupGuard.postpone(found, binding)
        }
        store.close()

        reopened(path) { restarted ->
            val (state, generation) = restarted.loadWithGeneration()
            val binding = BackupBinding("account-a", generation)
            assertEquals(BackupChoice.POSTPONED, state.choice)
            assertFalse(BackupGuard.canUpload(state, binding))
            assertEquals(BackupAction.AwaitChoice("remote", postponed = true), BackupGuard.nextAction(state, binding))
        }
    }

    @Test
    fun `background worker reloads the stored state and does not upload before the choice`() = storeTest { store, path ->
        BackupStateStore(store).save(BackupGuard.onSearchFoundCopy(BackupGuard.onSearchStarted(BackupState.INITIAL, accountA), accountA, "remote"))
        store.close()

        // A background run without UI: only the store and the current account from auth.
        reopened(path) { worker ->
            val (state, generation) = worker.loadWithGeneration()
            val current = BackupBinding(accountA.accountId, generation)
            assertFalse(BackupGuard.canUpload(state, current))
            assertFalse(BackupGuard.canUpload(state, null))
            assertFalse(BackupGuard.canUpload(state, BackupBinding(accountB.accountId, generation)))
        }
    }

    @Test
    fun `restored dataset with a new generation resets the stored permission`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        states.save(BackupGuard.onSearchFoundNone(BackupGuard.onSearchStarted(BackupState.INITIAL, accountA), accountA))
        assertTrue(BackupGuard.canUpload(states.load(), accountA))

        // The generation changed (the restore committed but the guard state did not): the permission is void.
        store.write { it.execSQL("UPDATE device_state SET dataGeneration = dataGeneration + 1") }
        val (state, generation) = states.loadWithGeneration()
        assertEquals(2L, generation)
        assertFalse(BackupGuard.canUpload(state, BackupBinding(accountA.accountId, generation)))

        val rebound = states.update { current, gen -> BackupGuard.bind(current, BackupBinding(accountA.accountId, gen)) }
        assertEquals(BackupState.freshFor(BackupBinding(accountA.accountId, 2)), rebound)
        assertEquals(rebound, states.load())
    }

    @Test
    fun `restore can record its state in the same transaction as the generation bump`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        states.save(BackupGuard.onSearchFoundCopy(BackupGuard.onSearchStarted(BackupState.INITIAL, accountA), accountA, "remote"))

        // Restore mode bumps the generation itself at the end of the same transaction.
        store.restore { db ->
            val restored = BackupBinding(accountA.accountId, db.readDataGeneration() + 1)
            db.writeBackupState(BackupGuard.onRestoreSucceeded(restored, "remote", noon))
        }
        val (state, generation) = states.loadWithGeneration()
        assertEquals(2L, generation)
        assertTrue(BackupGuard.canUpload(state, BackupBinding(accountA.accountId, generation)))
        assertEquals(noon, state.lastSuccessfulBackupAt)
    }

    @Test
    fun `update receives the current state and generation and returns the stored result`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        store.write { it.execSQL("UPDATE device_state SET dataGeneration = 3") }
        var seen: Pair<BackupState, Long>? = null
        val result = states.update { state, generation ->
            seen = state to generation
            BackupGuard.bind(state, BackupBinding("account-a", generation))
        }
        assertEquals(BackupState.INITIAL to 3L, assertNotNull(seen))
        assertEquals(3L, result.boundGeneration)
        assertEquals(result, states.load())
    }

    @Test
    fun `failed update leaves the stored state unchanged`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        val before = BackupGuard.postpone(
            BackupGuard.onSearchFoundCopy(BackupGuard.onSearchStarted(BackupState.INITIAL, accountA), accountA, "remote"),
            accountA,
        )
        states.save(before)
        assertFailsWith<Boom> { states.update { _, _ -> throw Boom() } }
        assertEquals(before, states.load())
    }

    @Test
    fun `concurrent updates are applied one after another`() = storeTest { store, _ ->
        val states = BackupStateStore(store)
        states.save(BackupGuard.onSearchStarted(BackupState.INITIAL, accountA))
        // Each update sees the previous result: only one finds the state still SEARCHING.
        val applied = (1..20).map { i ->
            async {
                var hit = false
                states.update { state, _ ->
                    val next = BackupGuard.onSearchFoundCopy(state, accountA, "file-$i")
                    hit = next != state
                    next
                }
                hit
            }
        }.awaitAll()
        assertEquals(1, applied.count { it })
        assertEquals(BackupSearchState.FOUND_COPY, states.load().searchState)
    }
}
