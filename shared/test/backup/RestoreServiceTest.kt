package app.servicecalendar.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.GatedDispatcher
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.ReportRepository
import app.servicecalendar.data.WorkEntryRepository
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.StorageBusy
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Restoring a snapshot on top of a real database. */
class RestoreServiceTest {
    private val nov1 = LocalDate(2026, 11, 1)
    private val nov2 = LocalDate(2026, 11, 2)

    private class Env(val store: DataStore, val path: String, val service: RestoreService) {
        val catalog = CatalogRepository(store)
        val entries = WorkEntryRepository(store)
        val reports = ReportRepository(store)
    }

    private fun restoreTest(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend CoroutineScope.(Env) -> Unit,
    ) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path, dispatcher)
                try {
                    withTimeout(60_000) { block(Env(store, path, RestoreService(store))) }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    /**
     * A local dataset "before the restore" that differs from the snapshot: procedure "Локальна", place
     * "Локальне" with a rate and an entry on 01.11 with quantity 5. ID 1 matches the snapshot but
     * refers to different rows.
     */
    private suspend fun Env.seedLocal(): Long {
        val procedure = catalog.createProcedure("Локальна")
        val place = catalog.savePlace(PlaceDraft(null, "Локальне", null, TEST_COLOR_B, mapOf(procedure to 300L)))
        entries.save(nov1, place, mapOf(procedure to 5), store.currentGeneration())
        return place
    }

    private suspend fun Env.local(): BackupSnapshot = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)

    private fun SQLiteConnection.deviceStateRow(): Map<String, String?> =
        prepare("SELECT * FROM device_state WHERE id = 1").use { stmt ->
            check(stmt.step())
            (0 until stmt.getColumnCount()).associate { i ->
                stmt.getColumnName(i) to if (stmt.isNull(i)) null else stmt.getText(i)
            }
        }

    private fun invalidSnapshot(): BackupSnapshot {
        val base = sampleSnapshot()
        // A work line that references the non-existent entry 99.
        return base.copy(workLines = base.workLines + WorkLineDto(99, 1, 1, 1250))
    }

    @Test
    fun `inspect reports the copy and the local set without changing anything`() = restoreTest { env ->
        env.seedLocal()
        val revision = env.store.revision.value

        val report = env.service.inspect(sampleSnapshot(currencyCode = "EUR"))

        assertEquals(TEST_CREATED_AT, report.createdAt)
        assertEquals("EUR", report.currencyCode)
        assertEquals(DatasetCounts(procedures = 2, places = 2, placeRates = 3, workEntries = 4, workLines = 5), report.backup)
        assertEquals(DatasetCounts(procedures = 1, places = 1, placeRates = 1, workEntries = 1, workLines = 1), report.local)
        assertEquals("BRL", report.localCurrencyCode)
        assertTrue(report.differsFromLocal)
        assertEquals(1L, report.localGeneration)
        assertEquals(revision, env.store.revision.value)
        assertEquals(1L, env.store.currentGeneration())
        assertIs<RestoreState.Idle>(env.service.state.value)
    }

    @Test
    fun `inspect sees no difference after the same copy was restored`() = restoreTest { env ->
        val snapshot = snapshotWithArchivedRows()
        env.service.restore(snapshot)
        // Row order in the file does not matter, nor does the creation time.
        val shuffled = snapshot.copy(
            createdAt = "2026-11-10T08:00:00Z",
            procedures = snapshot.procedures.reversed(),
            workLines = snapshot.workLines.reversed(),
        )
        val report = env.service.inspect(shuffled)
        assertFalse(report.differsFromLocal)
        assertEquals(report.backup, report.local)
        assertEquals(2L, report.localGeneration)
        // A different currency means a different dataset.
        assertTrue(env.service.inspect(snapshot.copy(currencyCode = "EUR")).differsFromLocal)
    }

    @Test
    fun `restore writes scheduled counts and an entry with only a scheduled count`() = restoreTest { env ->
        env.seedLocal()
        val snapshot = snapshotWithScheduledOnlyEntry()
        env.service.restore(snapshot)
        assertEquals(snapshot, env.local())
        val nov4 = env.entries.dayCards(LocalDate(2026, 11, 4)).first()
        assertEquals(listOf(5), nov4.places.map { it.scheduled })
        assertEquals(8, env.entries.dayCards(nov1).first().places.single { it.place.id == 1L }.scheduled)
        assertFalse(env.service.inspect(snapshot).differsFromLocal)
    }

    @Test
    fun `a version 1 copy restores with no scheduled counts`() = restoreTest { env ->
        env.seedLocal()
        val root = Json.parseToJsonElement(SnapshotCodec.encode(sampleSnapshot())) as JsonObject
        val entries = (root["workEntries"] as JsonArray).map { e -> JsonObject((e as JsonObject) - "scheduledCount") }
        val v1Json = JsonObject(
            root + ("schemaVersion" to JsonPrimitive(1)) +
                ("workEntries" to JsonArray(entries)),
        ).toString()
        val parsed = parseAndValidate(v1Json)
        assertIs<SnapshotValidationResult.Valid>(parsed)
        env.service.restore(parsed.snapshot)
        val local = env.local()
        assertTrue(local.workEntries.all { it.scheduledCount == null })
        assertEquals(sampleSnapshot().workLines, local.workLines)
        assertEquals(BackupSnapshot.CURRENT_VERSION, local.schemaVersion)
        // The same data in a version 1 file is not reported as different from the local set.
        assertFalse(env.service.inspect(parsed.snapshot).differsFromLocal)
    }

    @Test
    fun `inspect rejects an invalid copy`() = restoreTest { env ->
        val error = assertFailsWith<InvalidSnapshotException> { env.service.inspect(invalidSnapshot()) }
        assertEquals(SnapshotInvalidReason.DANGLING_REFERENCE, error.invalid.reason)
    }

    @Test
    fun `restore replaces the whole set and bumps the generation once`() = restoreTest { env ->
        env.seedLocal()
        val revision = env.store.revision.value
        val snapshot = snapshotWithArchivedRows().copy(currencyCode = "EUR")

        val result = env.service.restore(snapshot)

        assertEquals(2L, result.generation)
        assertEquals(DatasetCounts.of(snapshot), result.restored)
        assertEquals(2L, env.store.currentGeneration())
        assertEquals(2L, env.store.dataGeneration.value)
        assertEquals(revision + 1, env.store.revision.value)
        assertFalse(env.store.isRestoring.value)
        assertEquals(RestoreState.Succeeded(result), env.service.state.value)
        // The whole dataset with original IDs, archived rows and currency; nothing of the local set remains.
        assertEquals(snapshot.copy(createdAt = TEST_CREATED_AT), env.local())
    }

    @Test
    fun `sample snapshot restores to the expected day and report totals`() = restoreTest { env ->
        env.seedLocal()
        env.service.restore(sampleSnapshot())

        val day = env.entries.dayCards(nov1).first()
        assertEquals(MinorAmount(9300), day.dayTotal)
        assertEquals(listOf(MinorAmount(5300), MinorAmount(4000)), day.places.sortedBy { it.place.id }.map { it.placeTotal })
        val report = env.reports.report(nov1, nov2)
        assertEquals(MinorAmount(6550), report.places.single { it.place.id == 1L }.total)
        assertEquals(MinorAmount(4000), report.places.single { it.place.id == 2L }.total)

        // New rows created after the restore get IDs above the restored ones.
        val procedure = env.catalog.createProcedure("Нова")
        val place = env.catalog.savePlace(PlaceDraft(null, "Нове", null, TEST_COLOR_A, mapOf(procedure to 100L)))
        val saved = env.entries.save(LocalDate(2026, 11, 5), place, mapOf(procedure to 1), env.store.currentGeneration())
        assertTrue(procedure > 2, "procedure id $procedure")
        assertTrue(place > 2, "place id $place")
        assertTrue(checkNotNull(saved.entryId) > 4, "entry id ${saved.entryId}")
    }

    @Test
    fun `restore leaves device state other than the generation untouched`() = restoreTest { env ->
        val before = env.store.read { it.deviceStateRow() }
        env.service.restore(sampleSnapshot())
        val after = env.store.read { it.deviceStateRow() }
        assertEquals(before - "dataGeneration", after - "dataGeneration")
        assertEquals("2", after["dataGeneration"])
    }

    @Test
    fun `an empty copy empties the local set`() = restoreTest { env ->
        env.seedLocal()
        val empty = sampleSnapshot().copy(
            procedures = emptyList(), places = emptyList(), placeRates = emptyList(),
            workEntries = emptyList(), workLines = emptyList(),
        )
        env.service.restore(empty)
        assertEquals(empty, env.local())
        assertEquals(2L, env.store.currentGeneration())
    }

    @Test
    fun `editor save with the old generation is rejected after restore`() = restoreTest { env ->
        val localPlace = env.seedLocal()
        val editor = env.entries.loadEditor(nov1, localPlace)
        assertEquals(1L, editor.generation)

        env.service.restore(sampleSnapshot())
        val revision = env.store.revision.value

        // A draft from the old editor: ID 1 now means "Огляд" and place A.
        assertFailsWith<StaleGeneration> {
            env.entries.save(nov1, localPlace, editor.rows.associate { it.procedureId to 9 }, editor.generation)
        }
        assertEquals(revision, env.store.revision.value)
        assertEquals(sampleSnapshot(), env.local())

        val reopened = env.entries.loadEditor(nov1, 1)
        assertEquals(2L, reopened.generation)
    }

    @Test
    fun `save delayed behind the restore transaction is rejected`() {
        val gate = GatedDispatcher()
        restoreTest(gate) { env ->
            val localPlace = env.seedLocal()
            val editor = env.entries.loadEditor(nov1, localPlace)
            val procedure = editor.rows.single().procedureId

            // The save has passed the mode check but reaches the Mutex only after the restore.
            gate.holding = true
            val delayedSave = async(Dispatchers.Default) {
                runCatching { env.entries.save(nov1, localPlace, mapOf(procedure to 7), editor.generation) }
            }
            withTimeout(5_000) { while (gate.heldCount() < 1) delay(5) }
            gate.holding = false

            env.service.restore(sampleSnapshot())
            gate.releaseHeld()

            assertIs<StaleGeneration>(delayedSave.await().exceptionOrNull())
            assertEquals(sampleSnapshot(), env.local())
        }
    }

    @Test
    fun `saves and backups are blocked while the restore runs`() = restoreTest { env ->
        val localPlace = env.seedLocal()
        val editor = env.entries.loadEditor(nov1, localPlace)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // A write already in progress holds the store; the restore waits for it in restore mode.
        val started = async(Dispatchers.Default) {
            env.store.write { db ->
                db.execSQL("UPDATE procedure SET name = 'Розпочата' WHERE id = 1")
                entered.complete(Unit)
                // At most 10 s so that a failed test cannot hang the run.
                runBlocking { withTimeout(10_000) { release.await() } }
            }
        }
        entered.await()
        val restoring = async(Dispatchers.Default) { env.service.restore(sampleSnapshot()) }
        withTimeout(5_000) { while (!env.store.isRestoring.value) delay(5) }
        assertIs<RestoreState.InProgress>(env.service.state.value)

        assertFailsWith<StorageBusy> {
            env.entries.save(nov1, localPlace, mapOf(editor.rows.single().procedureId to 1), editor.generation)
        }
        assertFailsWith<StorageBusy> { env.catalog.createProcedure("Під час відновлення") }
        assertFailsWith<StorageBusy> { env.service.restore(sampleSnapshot()) }
        // Reading a snapshot for a backup waits and sees only the restored dataset, not the old or a mixed one.
        val backupRead = async(Dispatchers.Default) { env.local() }
        delay(100)
        assertFalse(backupRead.isCompleted)

        release.complete(Unit)
        started.await()
        restoring.await()
        assertEquals(sampleSnapshot(), backupRead.await())
        assertIs<RestoreState.Succeeded>(env.service.state.value)
    }

    @Test
    fun `invalid copy is rejected before restore mode and keeps the local set`() = restoreTest { env ->
        env.seedLocal()
        val localBefore = env.local()
        val revision = env.store.revision.value

        val error = assertFailsWith<InvalidSnapshotException> { env.service.restore(invalidSnapshot()) }
        assertEquals(SnapshotInvalidReason.DANGLING_REFERENCE, error.invalid.reason)

        assertEquals(localBefore, env.local())
        assertEquals(1L, env.store.currentGeneration())
        assertEquals(revision, env.store.revision.value)
        assertEquals(RestoreState.Failed(error), env.service.state.value)
    }

    @Test
    fun `failure in the middle of inserting rolls everything back`() = restoreTest { env ->
        val localPlace = env.seedLocal()
        val editor = env.entries.loadEditor(nov1, localPlace)
        // A temp trigger lives on the store's only connection: inserting a work line fails midway.
        env.store.write {
            it.execSQL(
                "CREATE TEMP TRIGGER fail_restore BEFORE INSERT ON work_line WHEN NEW.entryId = 3 " +
                    "BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            )
        }
        val localBefore = env.local()
        val revision = env.store.revision.value

        val error = assertFails { env.service.restore(sampleSnapshot()) }

        assertEquals(localBefore, env.local())
        assertEquals(1L, env.store.currentGeneration())
        assertEquals(1L, env.store.dataGeneration.value)
        assertEquals(revision, env.store.revision.value)
        assertFalse(env.store.isRestoring.value)
        assertEquals(RestoreState.Failed(error), env.service.state.value)
        // The old editor's draft stays valid because the generation did not change.
        env.entries.save(nov1, localPlace, mapOf(editor.rows.single().procedureId to 6), editor.generation)

        // A retry succeeds once the cause is removed.
        env.store.write { it.execSQL("DROP TRIGGER temp.fail_restore") }
        env.service.restore(sampleSnapshot())
        assertEquals(sampleSnapshot(), env.local())
        assertEquals(2L, env.store.currentGeneration())
    }

    @Test
    fun `next launch reads the restored set and rejects old drafts`() = restoreTest { env ->
        val localPlace = env.seedLocal()
        val draftGeneration = env.entries.loadEditor(nov1, localPlace).generation
        env.service.restore(sampleSnapshot(currencyCode = "EUR"))
        env.store.close()

        val relaunched = DataStore(env.path)
        try {
            assertEquals(sampleSnapshot(currencyCode = "EUR"), SnapshotCodec.readSnapshot(relaunched, TEST_CREATED_AT))
            assertEquals(2L, relaunched.currentGeneration())
            assertEquals(2L, relaunched.dataGeneration.value)
            // A draft saved with the pre-restore generation is rejected.
            assertFailsWith<StaleGeneration> {
                WorkEntryRepository(relaunched).save(nov1, 1, mapOf(1L to 9), draftGeneration)
            }
            assertEquals(MinorAmount(9300), WorkEntryRepository(relaunched).dayCards(nov1).first().dayTotal)
        } finally {
            relaunched.close()
        }
    }

    private suspend fun assertFails(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError("Expected the restore to fail")
    }
}
