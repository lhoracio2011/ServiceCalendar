package app.servicecalendar.backup

import androidx.sqlite.execSQL
import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.GatedDispatcher
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import io.ktor.http.HttpStatusCode
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The backup coordinator on top of a real SQLite store, [FakeDriveServer] and [FakeGoogleAuthProvider]. */
class BackupCoordinatorTest {
    private val accountA = FakeGoogleAuthProvider.ACCOUNT_A
    private val accountB = FakeGoogleAuthProvider.ACCOUNT_B

    private class Env(val store: DataStore, val dispatcher: GatedDispatcher) {
        val auth = FakeGoogleAuthProvider(account = null)
        val drive = FakeDriveServer()
        var now: Instant = Instant.parse("2026-11-10T12:00:00Z")
        val states = BackupStateStore(store)
        val restoreService = RestoreService(store)
        val client = drive.client(auth)

        /** A new coordinator over the same store, as after a process restart. */
        fun newCoordinator(): BackupCoordinator =
            BackupCoordinator(auth, client, store, states, restoreService, now = { now })

        val coordinator = newCoordinator()
    }

    private fun coordinatorTest(block: suspend CoroutineScope.(Env) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val dispatcher = GatedDispatcher()
                val store = DataStore(path, dispatcher)
                try {
                    withTimeout(60_000) { block(Env(store, dispatcher)) }
                } finally {
                    dispatcher.holding = false
                    dispatcher.releaseHeld()
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(5.milliseconds) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    /** A local row that is not in the copy: the procedure "Локальна". */
    private suspend fun Env.seedLocal() {
        store.write { it.execSQL("INSERT INTO procedure (name, archived) VALUES ('Локальна', 0)") }
    }

    private suspend fun Env.localSnapshot(): BackupSnapshot = SnapshotCodec.readSnapshot(store, createdAt = "")

    private fun BackupSnapshot.withoutTime(): BackupSnapshot = copy(createdAt = "")

    private fun copyJson(createdAt: String = "2026-11-10T06:00:00Z"): String =
        SnapshotCodec.encode(sampleSnapshot(createdAt = createdAt))

    /** Connects an account through the visible consent UI. */
    private suspend fun Env.connect(account: GoogleAccount = FakeGoogleAuthProvider.ACCOUNT_A): BackupOutcome {
        auth.consentResult = ConsentResult.Granted(account)
        return coordinator.connectAccount()
    }

    @Test
    fun `connecting with no copy on Drive searches then creates the initial copy`() = coordinatorTest { env ->
        env.seedLocal()

        assertEquals(BackupOutcome.Uploaded, env.connect())

        assertEquals(listOf("list", "create"), env.drive.log)
        val fileId = env.drive.fileIds.single()
        val uploaded = SnapshotCodec.decode(checkNotNull(env.drive.content(fileId)))
        assertEquals(env.localSnapshot(), uploaded.withoutTime())
        assertEquals(utcTimestamp(env.now), uploaded.createdAt)
        val state = env.states.load()
        assertEquals(BackupSearchState.FOUND_NONE, state.searchState)
        assertEquals(fileId, state.driveFileId)
        assertEquals(env.now, state.lastSuccessfulBackupAt)
        assertEquals(env.drive.revision(fileId), state.remoteRevision) // marker of the created copy
        val ui = env.coordinator.state.value
        assertEquals(BackupPhase.ACTIVE, ui.phase)
        assertEquals(accountA.displayLabel, ui.accountLabel)
        assertEquals(env.now, ui.lastSuccessfulBackupAt)
        assertNull(ui.busy)
        assertNull(ui.lastError)
        assertEquals(1, env.auth.consentRequests)
    }

    @Test
    fun `connecting with an existing copy awaits the choice and uploads nothing`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.connect())

        assertEquals(listOf("list"), env.drive.log)
        assertEquals(copyJson(), env.drive.content(fileId))
        val state = env.states.load()
        assertEquals(BackupSearchState.FOUND_COPY, state.searchState)
        assertEquals(fileId, state.driveFileId)
        assertEquals(BackupPhase.AWAITING_CHOICE, env.coordinator.state.value.phase)
        assertFalse(env.coordinator.state.value.choicePostponed)
    }

    @Test
    fun `background run and restart keep the pending choice and never upload`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())
        env.connect()

        assertEquals(BackupOutcome.Completed, env.coordinator.postpone())
        assertTrue(env.coordinator.state.value.choicePostponed)

        val restarted = env.newCoordinator()
        env.now += 48.hours
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), restarted.runScheduled())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), restarted.runOnAppOpen())

        assertEquals(listOf("list"), env.drive.log)
        assertEquals(copyJson(), env.drive.content(fileId))
        assertEquals(BackupPhase.AWAITING_CHOICE, restarted.state.value.phase)
        assertTrue(restarted.state.value.choicePostponed)
    }

    @Test
    fun `cancelled consent changes nothing`() = coordinatorTest { env ->
        env.auth.consentResult = ConsentResult.Cancelled

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.CANCELLED), env.coordinator.connectAccount())

        assertTrue(env.drive.log.isEmpty())
        assertEquals(BackupState.INITIAL, env.states.load())
        assertEquals(BackupPhase.NOT_CONNECTED, env.coordinator.state.value.phase)
    }

    @Test
    fun `search failure keeps the guard and the next run searches again`() = coordinatorTest { env ->
        env.drive.interceptor = { label, _ -> if (label == "list") respond("", HttpStatusCode.ServiceUnavailable) else null }

        assertEquals(BackupOutcome.Retry(BackupErrorKind.DRIVE_UNAVAILABLE), env.connect())

        assertEquals(listOf("list", "list"), env.drive.log) // one attempt and one retry
        assertEquals(BackupSearchState.FAILED, env.states.load().searchState)
        assertEquals(BackupPhase.SEARCH_FAILED, env.coordinator.state.value.phase)
        assertEquals(BackupErrorKind.DRIVE_UNAVAILABLE, env.coordinator.state.value.lastError)

        env.drive.interceptor = null
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())
        assertEquals(listOf("list", "list", "list", "create"), env.drive.log)
        assertNull(env.coordinator.state.value.lastError)
    }

    @Test
    fun `several copies offer the newest one that passes validation`() = coordinatorTest { env ->
        val valid = env.drive.put(copyJson(), modified = Instant.parse("2026-11-05T00:00:00Z"))
        env.drive.put("""{"schemaVersion":1}""", modified = Instant.parse("2026-11-09T00:00:00Z"))

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.connect())

        assertEquals(valid, env.states.load().driveFileId)
    }

    @Test
    fun `create with an unknown result is followed by a search instead of a second create`() = coordinatorTest { env ->
        var failCreate = true
        env.drive.interceptor = { label, request ->
            if (label == "create" && failCreate) {
                failCreate = false
                // Drive created the file but the response never arrived.
                env.drive.put(FakeDriveServer.multipartContent(request))
                throw kotlinx.io.IOException("connection reset")
            } else {
                null
            }
        }

        assertEquals(BackupOutcome.Retry(BackupErrorKind.NETWORK), env.connect())
        assertNull(env.states.load().lastSuccessfulBackupAt)

        // The next attempt searches before creating and finds its own copy, so no second copy is made.
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.newCoordinator().runScheduled())
        assertEquals(listOf("list", "create", "list"), env.drive.log)
        assertEquals(1, env.drive.fileIds.size)
    }

    @Test
    fun `missing copy file leads to a new search and a new initial copy`() = coordinatorTest { env ->
        env.connect()
        val first = env.drive.fileIds.single()
        env.now += 25.hours
        env.drive.delete(first)

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())

        assertEquals(listOf("list", "create", "meta:$first", "list", "create"), env.drive.log)
        val state = env.states.load()
        assertEquals(env.now, state.lastSuccessfulBackupAt)
        assertTrue(state.driveFileId != null && state.driveFileId != first)
    }

    @Test
    fun `daily update is skipped within 24 hours without touching Drive`() = coordinatorTest { env ->
        env.connect()
        val tokenCalls = env.auth.tokenCalls
        env.now += 23.hours

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.coordinator.runScheduled())

        assertEquals(listOf("list", "create"), env.drive.log)
        assertEquals(tokenCalls, env.auth.tokenCalls)
    }

    @Test
    fun `data changed after a fresh backup is uploaded by the next automatic run`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.now += 1.hours
        env.seedLocal()

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runOnAppOpen())

        assertEquals(listOf("list", "create", "meta:$fileId", "update:$fileId"), env.drive.log)
        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())
        // Nothing changed since: the next runs within 24 hours do not touch Drive, also after a restart.
        env.now += 1.hours
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.coordinator.runScheduled())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.newCoordinator().runOnAppOpen())
        assertEquals(4, env.drive.log.size)
    }

    @Test
    fun `leaving the app uploads changed data and skips unchanged data`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.now += 1.hours

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.coordinator.runOnLeave())
        assertEquals(listOf("list", "create"), env.drive.log)

        env.seedLocal()
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runOnLeave())
        assertEquals(listOf("list", "create", "meta:$fileId", "update:$fileId"), env.drive.log)
        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())

        env.now += 1.hours
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.newCoordinator().runOnLeave())
        assertEquals(4, env.drive.log.size)
    }

    @Test
    fun `pending changes are reported only for a signed in account with changed data`() = coordinatorTest { env ->
        env.seedLocal()
        assertFalse(env.coordinator.hasChangesToBackUp()) // not signed in

        env.connect()
        assertFalse(env.coordinator.hasChangesToBackUp()) // just uploaded

        env.store.write { it.execSQL("INSERT INTO procedure (name, archived) VALUES ('Інша', 0)") }
        assertTrue(env.coordinator.hasChangesToBackUp())
    }

    @Test
    fun `leaving the app without an account does nothing`() = coordinatorTest { env ->
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED), env.coordinator.runOnLeave())
        assertTrue(env.drive.log.isEmpty())
        assertEquals(0, env.auth.consentRequests)
    }

    @Test
    fun `leaving the app never uploads while a restore decision is pending`() = coordinatorTest { env ->
        env.drive.put(copyJson())
        env.seedLocal()
        env.connect() // a copy exists: the user has to choose
        val log = env.drive.log.toList()
        env.now += 1.hours

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runOnLeave())
        assertEquals(log, env.drive.log)
    }

    @Test
    fun `price edit after a fresh backup counts as a change`() = coordinatorTest { env ->
        val catalog = CatalogRepository(env.store)
        val procedure = catalog.createProcedure("Exam")
        val place = catalog.savePlace(PlaceDraft(null, "Other", null, 0L, mapOf(procedure to 1000L)))
        env.connect()
        env.now += 1.hours

        catalog.savePlace(PlaceDraft(place, "Other", null, 0L, mapOf(procedure to 1100L)))

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())
        val uploaded = SnapshotCodec.decode(checkNotNull(env.drive.content(env.drive.fileIds.single())))
        assertEquals(listOf(PlaceRateDto(place, procedure, 1100L)), uploaded.placeRates)
    }

    @Test
    fun `state from before the change marker uploads once and then follows the marker`() = coordinatorTest { env ->
        env.connect()
        // An install updated from schema 4 has no marker for its last upload.
        env.store.write { it.execSQL("UPDATE device_state SET backupDataHash = NULL WHERE id = 1") }
        env.now += 1.hours

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runOnAppOpen())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.coordinator.runOnAppOpen())
    }

    @Test
    fun `back up now uploads even when the last backup is fresh`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.now += 1.hours
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.coordinator.syncNow())

        assertEquals(BackupOutcome.Uploaded, env.coordinator.backUpNow())

        assertEquals(listOf("list", "create", "meta:$fileId", "update:$fileId"), env.drive.log)
        assertEquals(env.now, env.states.load().lastSuccessfulBackupAt)
        assertEquals(env.now, env.coordinator.state.value.lastSuccessfulBackupAt)
    }

    @Test
    fun `back up now without an account is skipped`() = coordinatorTest { env ->
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED), env.coordinator.backUpNow())
        assertTrue(env.drive.log.isEmpty())
    }

    @Test
    fun `back up now while a choice is pending uploads nothing`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())
        env.connect()

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.backUpNow())

        assertEquals(listOf("list"), env.drive.log)
        assertEquals(copyJson(), env.drive.content(fileId))
    }

    @Test
    fun `daily update after 24 hours updates the same file`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.seedLocal()
        env.now += 24.hours

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())

        assertEquals(listOf("list", "create", "meta:$fileId", "update:$fileId"), env.drive.log)
        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())
        assertEquals(env.now, env.states.load().lastSuccessfulBackupAt)
    }

    @Test
    fun `daily update over an unchanged copy stores the revision it wrote`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        val created = checkNotNull(env.states.load().remoteRevision)
        env.now += 25.hours

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())

        val stored = env.states.load().remoteRevision
        assertEquals(env.drive.revision(fileId), stored)
        assertTrue(stored != created, "after a write the marker is that of the new content")
        // The next day nobody changed the copy, so the update goes through again.
        env.now += 25.hours
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())
        assertEquals(2, env.drive.log.count { it == "update:$fileId" })
    }

    @Test
    fun `copy updated by another device is not overwritten and the choice is offered`() = coordinatorTest { env ->
        env.seedLocal()
        env.connect()
        val fileId = env.drive.fileIds.single()
        // Phone B (same account) restored the copy, worked and updated it.
        val newer = copyJson(createdAt = "2026-11-11T09:00:00Z")
        env.drive.overwrite(fileId, newer)
        env.now += 25.hours
        val requests = env.drive.log.size

        // Background run on phone A: metadata only, no write and no UI.
        val background = env.newCoordinator()
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), background.runScheduled())

        assertEquals(listOf("meta:$fileId"), env.drive.log.drop(requests))
        assertEquals(newer, env.drive.content(fileId))
        assertEquals(1, env.auth.consentRequests)
        val state = env.states.load()
        assertEquals(BackupSearchState.FOUND_COPY, state.searchState)
        assertEquals(BackupChoice.NONE, state.choice)
        assertEquals(fileId, state.driveFileId)
        assertEquals(env.drive.revision(fileId), state.remoteRevision)
        assertFalse(state.dailyBackupsAllowed)

        // Later runs write nothing either; the screen shows the choice when opened.
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), background.runOnAppOpen())
        val screen = env.newCoordinator()
        screen.refresh()
        assertEquals(BackupPhase.AWAITING_CHOICE, screen.state.value.phase)
        assertFalse(screen.state.value.choicePostponed)
        assertEquals(listOf("meta:$fileId"), env.drive.log.drop(requests))

        // "Replace the copy with local data" overwrites the new content after a separate confirmation.
        val confirmation = assertNotNull(screen.requestReplace())
        assertEquals(BackupOutcome.Uploaded, screen.confirmReplace(confirmation))
        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())
        assertEquals(env.drive.revision(fileId), env.states.load().remoteRevision)
    }

    @Test
    fun `restoring the copy changed by another device stores its revision and resumes daily copies`() = coordinatorTest { env ->
        env.seedLocal()
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.drive.overwrite(fileId, copyJson(createdAt = "2026-11-11T09:00:00Z"))
        env.now += 25.hours
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runScheduled())

        assertEquals(BackupOutcome.Completed, env.coordinator.prepareRestore())
        assertEquals(BackupOutcome.Completed, env.coordinator.restoreCopy())

        assertEquals(sampleSnapshot().withoutTime(), env.localSnapshot())
        val state = env.states.load()
        assertEquals(BackupChoice.RESTORE, state.choice)
        assertEquals(env.drive.revision(fileId), state.remoteRevision)
        assertEquals(BackupPhase.ACTIVE, env.coordinator.state.value.phase)
        env.now = Instant.parse("2026-11-12T10:00:00Z")
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())
        assertEquals("update:$fileId", env.drive.log.last())
    }

    @Test
    fun `revision is taken before the download so a change in between leads to the choice`() = coordinatorTest { env ->
        val fileId = env.drive.put(copyJson())
        env.connect()
        val before = env.drive.revision(fileId)
        // Another device writes the copy exactly between the metadata call and the content download.
        env.drive.interceptor = { label, _ ->
            if (label == "get:$fileId") env.drive.overwrite(fileId, copyJson(createdAt = "2026-11-10T07:00:00Z"))
            null
        }
        env.coordinator.prepareRestore()
        env.drive.interceptor = null
        assertEquals(BackupOutcome.Completed, env.coordinator.restoreCopy())
        assertEquals(before, env.states.load().remoteRevision)

        env.now += 25.hours
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runScheduled())
        assertTrue(env.drive.log.none { it.startsWith("update:") })
    }

    @Test
    fun `confirmed replace of a copy that changed after the search needs a new confirmation`() = coordinatorTest { env ->
        val fileId = env.drive.put(copyJson())
        env.connect()
        val confirmation = assertNotNull(env.coordinator.requestReplace())
        env.drive.overwrite(fileId, copyJson(createdAt = "2026-11-10T07:00:00Z"))

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.confirmReplace(confirmation))

        assertTrue(env.drive.log.none { it.startsWith("update:") })
        assertEquals(BackupChoice.NONE, env.states.load().choice)
        assertEquals(env.drive.revision(fileId), env.states.load().remoteRevision)
        assertEquals(BackupPhase.AWAITING_CHOICE, env.coordinator.state.value.phase)

        assertEquals(BackupOutcome.Uploaded, env.coordinator.confirmReplace(assertNotNull(env.coordinator.requestReplace())))
        assertEquals(1, env.drive.log.count { it == "update:$fileId" })
    }

    @Test
    fun `stored state without a revision adopts the copy this device wrote last`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        // State stored before schema v3: there is no revision marker.
        env.states.save(env.states.load().copy(remoteRevision = null))
        env.now += 25.hours

        assertEquals(BackupOutcome.Uploaded, env.newCoordinator().runScheduled())

        assertEquals(listOf("meta:$fileId", "update:$fileId"), env.drive.log.drop(2))
        assertEquals(env.drive.revision(fileId), env.states.load().remoteRevision)
    }

    @Test
    fun `stored state without a revision does not overwrite a later copy of another device`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        val lastSuccess = env.now
        env.states.save(env.states.load().copy(remoteRevision = null))
        val newer = copyJson(createdAt = "2026-11-10T18:00:00Z")
        env.drive.overwrite(fileId, newer, modified = lastSuccess + BackupGuard.LEGACY_CLOCK_TOLERANCE + 1.hours)
        env.now += 25.hours

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.newCoordinator().runScheduled())

        assertEquals(listOf("meta:$fileId"), env.drive.log.drop(2))
        assertEquals(newer, env.drive.content(fileId))
        val state = env.states.load()
        assertEquals(BackupSearchState.FOUND_COPY, state.searchState)
        assertEquals(env.drive.revision(fileId), state.remoteRevision)
    }

    @Test
    fun `missed background window is made up when the app opens`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.now += 30.hours

        val opened = env.newCoordinator()
        assertEquals(BackupOutcome.Uploaded, opened.runOnAppOpen())

        assertEquals("update:$fileId", env.drive.log.last())
        assertEquals(env.now, opened.state.value.lastSuccessfulBackupAt)
    }

    @Test
    fun `success is recorded only after Drive confirms the upload`() = coordinatorTest { env ->
        env.connect()
        val firstSuccess = env.now
        env.now += 25.hours

        env.drive.interceptor = { label, _ -> if (label.startsWith("update:")) respond("", HttpStatusCode.InternalServerError) else null }
        assertEquals(BackupOutcome.Retry(BackupErrorKind.DRIVE_UNAVAILABLE), env.coordinator.runScheduled())
        assertEquals(firstSuccess, env.states.load().lastSuccessfulBackupAt)

        env.drive.interceptor = { label, _ -> if (label.startsWith("update:")) throw kotlinx.io.IOException("offline") else null }
        assertEquals(BackupOutcome.Retry(BackupErrorKind.NETWORK), env.coordinator.runScheduled())
        assertEquals(firstSuccess, env.states.load().lastSuccessfulBackupAt)
        assertEquals(firstSuccess, env.coordinator.state.value.lastSuccessfulBackupAt)
        assertEquals(BackupErrorKind.NETWORK, env.coordinator.state.value.lastError)

        env.drive.interceptor = null
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())
        assertEquals(env.now, env.states.load().lastSuccessfulBackupAt)
    }

    @Test
    fun `lost permission in the background ends quietly without requesting UI`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        env.auth.tokenResult = AccessTokenResult.NeedsUserAction
        val consents = env.auth.consentRequests

        assertEquals(BackupOutcome.NeedsUserAction, env.coordinator.runScheduled())

        assertEquals(consents, env.auth.consentRequests)
        assertEquals(listOf("list", "create"), env.drive.log)
        assertEquals(BackupErrorKind.NEEDS_USER_ACTION, env.coordinator.state.value.lastError)
        assertFalse(BackupOutcome.NeedsUserAction.shouldRetry)
    }

    @Test
    fun `revoked token on Drive in the background also ends without UI`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        env.drive.interceptor = { label, _ -> if (label.startsWith("update:")) respond("", HttpStatusCode.Unauthorized) else null }

        assertEquals(BackupOutcome.NeedsUserAction, env.coordinator.runScheduled())

        assertEquals(1, env.auth.consentRequests) // only from connecting
        assertEquals(listOf(FakeGoogleAuthProvider.TOKEN), env.auth.invalidated)
    }

    @Test
    fun `background run without an account is skipped`() = coordinatorTest { env ->
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED), env.coordinator.runScheduled())
        assertTrue(env.drive.log.isEmpty())
        assertEquals(0, env.auth.consentRequests)
    }

    @Test
    fun `changing the account requires a new search before any upload`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        env.drive.put(copyJson()) // another account's copy in the same fake Drive
        env.auth.account = accountB

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runScheduled())

        val afterSwitch = env.drive.log.drop(2)
        assertEquals("list", afterSwitch.first())
        assertTrue(afterSwitch.none { it == "create" || it.startsWith("update:") })
        assertEquals(accountB.id, env.states.load().accountId)
        assertEquals(BackupSearchState.FOUND_COPY, env.states.load().searchState)
    }

    @Test
    fun `account switched mid-run sends nothing with the new token and changes no state`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        val before = env.states.load()
        val requests = env.drive.log.size
        // Consent switches to account B exactly when a run bound to A takes its token.
        env.auth.beforeTokenIssued = { env.auth.account = accountB }

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE), env.coordinator.runScheduled())

        assertEquals(requests, env.drive.log.size)
        assertEquals(before, env.states.load())
    }

    @Test
    fun `account switched after the search creates no copy under the old binding`() = coordinatorTest { env ->
        env.connect()
        val first = env.drive.fileIds.single()
        env.now += 25.hours
        env.drive.delete(first)
        // A's copy is gone: update -> 404 -> search. While the search runs, consent switches to account B.
        env.drive.interceptor = { label, _ ->
            if (label == "list") env.auth.account = accountB
            null
        }

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE), env.coordinator.runScheduled())

        assertEquals(listOf("list", "create", "meta:$first", "list"), env.drive.log)
        assertTrue(env.drive.fileIds.isEmpty())
        val state = env.states.load()
        assertEquals(accountA.id, state.accountId)
        assertNull(state.driveFileId)
    }

    @Test
    fun `automatic runs are skipped while consent is open`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        val requests = env.drive.log.size
        val (shown, answer) = env.auth.holdConsent()
        val connecting = async { env.coordinator.connectAccount() }
        shown.await()

        assertTrue(env.coordinator.isConsentInProgress)
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.CONSENT_IN_PROGRESS), env.coordinator.runOnAppOpen())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.CONSENT_IN_PROGRESS), env.coordinator.runScheduled())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.CONSENT_IN_PROGRESS), env.coordinator.syncNow())
        // A second consent is not shown.
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.BUSY), env.coordinator.connectAccount())
        assertEquals(2, env.auth.consentRequests)
        assertEquals(requests, env.drive.log.size)
        assertNull(env.coordinator.state.value.busy)

        answer.complete(ConsentResult.Cancelled)
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.CANCELLED), connecting.await())
        assertFalse(env.coordinator.isConsentInProgress)
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runOnAppOpen())
    }

    @Test
    fun `consent granted while another operation runs waits for it and syncs the new account`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())
        env.connect()
        val (shown, answer) = env.auth.holdConsent()
        val connecting = async { env.coordinator.connectAccount() }
        shown.await()
        // An operation that took the Mutex during consent is still running when consent is granted: hold the
        // content download (after the metadata call), i.e. a request already issued for account A.
        val armed = CompletableDeferred<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()
        env.drive.interceptor = { label, _ ->
            if (label == "meta:$fileId" && !armed.isCompleted) armed.complete(env.drive.holdNextRequest())
            null
        }
        val preparing = async { env.coordinator.prepareRestore() }
        val (entered, release) = armed.await()
        entered.await()
        answer.complete(ConsentResult.Granted(accountB))
        delay(50.milliseconds)

        assertFalse(connecting.isCompleted, "Consent must not be rejected as BUSY")
        assertTrue(env.coordinator.isConsentInProgress)
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.CONSENT_IN_PROGRESS), env.coordinator.runOnAppOpen())

        release.complete(Unit)
        assertEquals(BackupOutcome.Completed, preparing.await())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), connecting.await())

        assertEquals(listOf("list", "meta:$fileId", "get:$fileId", "list"), env.drive.log)
        val state = env.states.load()
        assertEquals(accountB.id, state.accountId)
        assertEquals(BackupSearchState.FOUND_COPY, state.searchState)
        val ui = env.coordinator.state.value
        assertEquals(accountB.displayLabel, ui.accountLabel)
        assertEquals(BackupPhase.AWAITING_CHOICE, ui.phase)
        assertNull(ui.restorePreview) // the preview was prepared for account A
        assertFalse(env.coordinator.isConsentInProgress)
    }

    @Test
    fun `second operation while one is running is rejected as busy`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        val (entered, release) = env.drive.holdNextRequest()

        val running = async { env.coordinator.runScheduled() }
        entered.await()

        assertEquals(BackupOperation.UPLOADING, env.coordinator.state.value.busy)
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.BUSY), env.coordinator.runOnAppOpen())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.BUSY), env.coordinator.syncNow())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.BUSY), env.coordinator.prepareRestore())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.BUSY), env.coordinator.restoreCopy())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.BUSY), env.coordinator.connectAccount())

        release.complete(Unit)
        assertEquals(BackupOutcome.Uploaded, running.await())
        assertEquals(1, env.drive.log.count { it.startsWith("update:") })
        assertNull(env.coordinator.state.value.busy)
    }

    @Test
    fun `no upload while a restore is in progress`() = coordinatorTest { env ->
        env.connect()
        env.now += 25.hours
        val requests = env.drive.log.size
        env.dispatcher.holding = true
        val restore = async { env.restoreService.restore(sampleSnapshot()) }
        awaitUntil("restore did not start") { env.store.isRestoring.value }

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.RESTORING), env.coordinator.runScheduled())
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.RESTORING), env.coordinator.runOnAppOpen())

        env.dispatcher.holding = false
        env.dispatcher.releaseHeld()
        restore.await()
        assertEquals(requests, env.drive.log.size)
    }

    @Test
    fun `restore starting during an upload makes it retry later instead of failing`() = coordinatorTest { env ->
        env.connect()
        val firstSuccess = env.now
        env.now += 25.hours
        val (entered, release) = env.drive.holdNextRequest()
        val running = async { env.coordinator.runScheduled() }
        entered.await()

        env.dispatcher.holding = true
        val restore = async { env.restoreService.restore(sampleSnapshot()) }
        awaitUntil("restore did not start") { env.store.isRestoring.value }
        release.complete(Unit)
        // Writing the state after Drive responds fails with StorageBusy; the UI update waits for the store.
        awaitUntil("coordinator did not reach the store") { env.dispatcher.heldCount() >= 2 }
        env.dispatcher.holding = false
        env.dispatcher.releaseHeld()

        assertEquals(BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY), running.await())
        restore.await()
        assertEquals(firstSuccess, env.states.load().lastSuccessfulBackupAt)
        assertTrue(BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY).shouldRetry)

        // A new generation voids the old binding: search first, never write over the copy.
        val updates = env.drive.log.count { it.startsWith("update:") }
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runScheduled())
        assertEquals(updates, env.drive.log.count { it.startsWith("update:") })
    }

    @Test
    fun `restoring the copy replaces local data and allows daily copies atomically`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson(createdAt = "2026-11-10T06:00:00Z"))
        env.connect()

        assertEquals(BackupOutcome.Completed, env.coordinator.prepareRestore())
        val preview = assertNotNull(env.coordinator.state.value.restorePreview)
        assertEquals(DatasetCounts(2, 2, 3, 4, 5), preview.backup)
        assertEquals(1, preview.local.procedures)
        assertTrue(preview.differsFromLocal)
        assertEquals(1L, env.store.currentGeneration()) // previewing changes nothing

        assertEquals(BackupOutcome.Completed, env.coordinator.restoreCopy())

        assertEquals(sampleSnapshot().withoutTime(), env.localSnapshot())
        assertEquals(2L, env.store.currentGeneration())
        assertEquals(
            BackupState(
                accountId = accountA.id,
                searchState = BackupSearchState.FOUND_COPY,
                choice = BackupChoice.RESTORE,
                driveFileId = fileId,
                lastSuccessfulBackupAt = Instant.parse("2026-11-10T06:00:00Z"),
                boundGeneration = 2,
                remoteRevision = env.drive.revision(fileId), // marker of the restored copy
            ),
            env.states.load(),
        )
        val ui = env.coordinator.state.value
        assertEquals(BackupPhase.ACTIVE, ui.phase)
        assertNull(ui.restorePreview)
        assertEquals(listOf("list", "meta:$fileId", "get:$fileId"), env.drive.log)

        // The copy is fresh: the daily attempt is skipped, and a day later it updates the same copy.
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE), env.coordinator.runScheduled())
        env.now = Instant.parse("2026-11-11T06:00:00Z")
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())
        assertEquals("update:$fileId", env.drive.log.last())
    }

    @Test
    fun `restore without a prepared copy is not applicable`() = coordinatorTest { env ->
        env.drive.put(copyJson())
        env.connect()

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE), env.coordinator.restoreCopy())

        env.coordinator.prepareRestore()
        env.coordinator.cancelRestore()
        assertNull(env.coordinator.state.value.restorePreview)
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE), env.coordinator.restoreCopy())
        assertEquals(1L, env.store.currentGeneration())
    }

    @Test
    fun `invalid copy cannot be restored and local data stays`() = coordinatorTest { env ->
        env.seedLocal()
        env.drive.put("""{"schemaVersion":1,"createdAt":"x"}""")
        env.connect()
        val before = env.localSnapshot()

        assertEquals(BackupOutcome.Failed(BackupErrorKind.INVALID_COPY), env.coordinator.prepareRestore())

        assertNull(env.coordinator.state.value.restorePreview)
        assertEquals(BackupErrorKind.INVALID_COPY, env.coordinator.state.value.lastError)
        assertEquals(before, env.localSnapshot())
        assertEquals(BackupPhase.AWAITING_CHOICE, env.coordinator.state.value.phase)
    }

    @Test
    fun `replacing the copy needs a separate confirmation then uploads over it`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())
        env.connect()

        val confirmation = assertNotNull(env.coordinator.requestReplace())
        // The request alone allows nothing.
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runScheduled())

        assertEquals(BackupOutcome.Uploaded, env.coordinator.confirmReplace(confirmation))

        assertEquals(listOf("list", "meta:$fileId", "update:$fileId"), env.drive.log)
        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())
        assertEquals(BackupChoice.REPLACE_CONFIRMED, env.states.load().choice)
        assertEquals(env.now, env.states.load().lastSuccessfulBackupAt)
        assertNull(env.coordinator.requestReplace())
    }

    @Test
    fun `replace confirmation issued for another account is ignored`() = coordinatorTest { env ->
        env.drive.put(copyJson())
        env.connect()
        val confirmation = assertNotNull(env.coordinator.requestReplace())
        env.connect(accountB)

        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.confirmReplace(confirmation))

        assertTrue(env.drive.log.none { it.startsWith("update:") })
        assertEquals(BackupChoice.NONE, env.states.load().choice)
    }

    @Test
    fun `failure while writing the backup state rolls back the whole restore`() = coordinatorTest { env ->
        env.seedLocal()
        val before = env.localSnapshot()
        val stateBefore = env.states.load()

        assertFailsWith<IllegalStateException> {
            env.restoreService.restore(sampleSnapshot()) { error("failure writing the state") }
        }

        assertEquals(before, env.localSnapshot())
        assertEquals(1L, env.store.currentGeneration())
        assertEquals(stateBefore, env.states.load())
    }

    @Test
    fun `ui state hides the account label in toString and shows sign out`() = coordinatorTest { env ->
        env.connect()
        assertFalse(accountA.displayLabel in env.coordinator.state.value.toString())

        env.coordinator.disconnect()

        assertEquals(1, env.auth.signOutCalls)
        assertEquals(BackupPhase.NOT_CONNECTED, env.coordinator.state.value.phase)
        assertNull(env.coordinator.state.value.accountLabel)
        // Signing in again with the same account keeps the state: the copy is not created twice.
        env.connect()
        assertEquals(1, env.drive.fileIds.size)
        assertEquals(BackupPhase.ACTIVE, env.coordinator.state.value.phase)
    }

    private fun forbidden(reason: String) =
        """{"error":{"code":403,"errors":[{"reason":"$reason"}],"status":"PERMISSION_DENIED"}}"""

    @Test
    fun `confirmed replace within 24 hours of this device's backup uploads right away`() = coordinatorTest { env ->
        env.seedLocal()
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.drive.overwrite(fileId, copyJson(createdAt = "2026-11-10T13:00:00Z"))
        env.now += 2.hours
        // Back Up Now sees the other device's copy and asks instead of overwriting it.
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.backUpNow())

        assertEquals(BackupOutcome.Uploaded, env.coordinator.confirmReplace(assertNotNull(env.coordinator.requestReplace())))

        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())
        assertEquals(env.now, env.states.load().lastSuccessfulBackupAt)
    }

    @Test
    fun `cloud project switch with a saved account and an empty Drive keeps local data and uploads a first copy`() =
        coordinatorTest { env ->
            env.seedLocal()
            env.connect()
            val oldFile = env.drive.fileIds.single()
            val local = env.localSnapshot()
            // The new Cloud project has its own empty appDataFolder: the old file is not visible any more.
            env.drive.delete(oldFile)
            env.now += 1.hours
            env.seedLocal() // an edit after the switch

            assertEquals(BackupOutcome.Uploaded, env.newCoordinator().runOnAppOpen())

            assertEquals(listOf("meta:$oldFile", "list", "create"), env.drive.log.drop(2))
            val newFile = env.drive.fileIds.single()
            assertTrue(newFile != oldFile)
            assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(newFile))).withoutTime())
            assertEquals(local.procedures.size + 1, env.localSnapshot().procedures.size)
            assertEquals(1L, env.store.currentGeneration())
            val ui = env.coordinator.also { it.refresh() }.state.value
            assertEquals(BackupPhase.ACTIVE, ui.phase)
            assertNull(ui.restorePreview)
            assertNull(ui.lastError)
        }

    @Test
    fun `old project file reported as not authorized is treated as missing`() = coordinatorTest { env ->
        env.seedLocal()
        env.connect()
        val oldFile = env.drive.fileIds.single()
        env.drive.delete(oldFile)
        env.drive.interceptor = { label, _ ->
            if (label == "meta:$oldFile") respond(forbidden("appNotAuthorizedToFile"), HttpStatusCode.Forbidden) else null
        }
        env.now += 25.hours

        assertEquals(BackupOutcome.Uploaded, env.coordinator.runScheduled())

        assertEquals(listOf("meta:$oldFile", "list", "create"), env.drive.log.drop(2))
        assertEquals(env.localSnapshot(), SnapshotCodec.decode(checkNotNull(env.drive.content(env.drive.fileIds.single()))).withoutTime())
    }

    @Test
    fun `cloud project switch that needs consent again restores access then uploads without touching local data`() =
        coordinatorTest { env ->
            env.seedLocal()
            env.connect()
            val oldFile = env.drive.fileIds.single()
            env.drive.delete(oldFile)
            env.seedLocal()
            val local = env.localSnapshot()
            // The new OAuth client has no grant yet: background runs end quietly and the screen offers Restore Access.
            env.auth.tokenResult = AccessTokenResult.NeedsUserAction
            assertEquals(BackupOutcome.NeedsUserAction, env.coordinator.runOnAppOpen())
            assertEquals(BackupErrorKind.NEEDS_USER_ACTION, env.coordinator.state.value.lastError)
            assertEquals(1, env.auth.consentRequests)

            env.auth.tokenResult = null
            assertEquals(BackupOutcome.Uploaded, env.connect())

            assertEquals(local, env.localSnapshot())
            assertEquals(local, SnapshotCodec.decode(checkNotNull(env.drive.content(env.drive.fileIds.single()))).withoutTime())
            assertNull(env.coordinator.state.value.lastError)
            assertEquals(BackupPhase.ACTIVE, env.coordinator.state.value.phase)
        }

    @Test
    fun `cloud project switch where the new project already has a copy asks before anything is written`() =
        coordinatorTest { env ->
            env.seedLocal()
            env.connect()
            val oldFile = env.drive.fileIds.single()
            env.drive.delete(oldFile)
            val other = env.drive.put(copyJson()) // written by another phone under the new project
            val local = env.localSnapshot()
            env.now += 1.hours
            env.seedLocal()

            assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), env.coordinator.runOnAppOpen())

            assertEquals(copyJson(), env.drive.content(other))
            assertEquals(listOf("meta:$oldFile", "list"), env.drive.log.drop(2))
            assertEquals(local.procedures.size + 1, env.localSnapshot().procedures.size)
            assertEquals(BackupPhase.AWAITING_CHOICE, env.coordinator.state.value.phase)
            assertEquals(other, env.states.load().driveFileId)
        }

    @Test
    fun `damaged truncated newer or incomplete copies are rejected and the local data stays`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())
        env.connect()
        val before = env.localSnapshot()
        val good = copyJson()
        val dangling = SnapshotCodec.encode(
            sampleSnapshot().let { it.copy(placeRates = it.placeRates + PlaceRateDto(placeId = 99, procedureId = 1, amountMinor = 100)) },
        )
        val lineWithoutEntry = SnapshotCodec.encode(
            sampleSnapshot().let { it.copy(workEntries = it.workEntries.drop(1)) },
        )
        val cases = mapOf(
            "truncated" to good.take(good.length / 2),
            "corrupted" to good.replace("\"amountMinor\":1250", "\"amountMinor\":\"12,50\""),
            "not json" to "\u0000\u0001garbage",
            "newer format" to good.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            "unknown old format" to good.replace("\"schemaVersion\":2", "\"schemaVersion\":0"),
            "rate for a missing place" to dangling,
            "line without its entry" to lineWithoutEntry,
        )
        for ((name, content) in cases) {
            env.drive.overwrite(fileId, content)

            assertEquals(BackupOutcome.Failed(BackupErrorKind.INVALID_COPY), env.coordinator.prepareRestore(), name)

            assertNull(env.coordinator.state.value.restorePreview, name)
            assertEquals(BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE), env.coordinator.restoreCopy(), name)
            assertEquals(before, env.localSnapshot(), name)
            assertEquals(1L, env.store.currentGeneration(), name)
            assertEquals(BackupPhase.AWAITING_CHOICE, env.coordinator.state.value.phase, name)
        }
    }

    @Test
    fun `failed download keeps the local data and the choice`() = coordinatorTest { env ->
        env.seedLocal()
        val fileId = env.drive.put(copyJson())
        env.connect()
        val before = env.localSnapshot()
        env.drive.interceptor = { label, _ -> if (label == "get:$fileId") throw kotlinx.io.IOException("offline") else null }

        assertEquals(BackupOutcome.Retry(BackupErrorKind.NETWORK), env.coordinator.prepareRestore())

        assertEquals(before, env.localSnapshot())
        assertEquals(1L, env.store.currentGeneration())
        assertNull(env.coordinator.state.value.restorePreview)
        assertEquals(BackupErrorKind.NETWORK, env.coordinator.state.value.lastError)
        assertEquals(BackupPhase.AWAITING_CHOICE, env.coordinator.state.value.phase)
    }

    @Test
    fun `failed upload keeps local data and the change is uploaded once Drive works again`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        val lastSuccess = env.states.load().lastSuccessfulBackupAt
        env.now += 1.hours
        env.seedLocal()
        val local = env.localSnapshot()
        val uploadedBefore = env.drive.content(fileId)

        env.drive.interceptor = { label, _ ->
            if (label == "update:$fileId") respond(forbidden("storageQuotaExceeded"), HttpStatusCode.Forbidden) else null
        }
        assertEquals(BackupOutcome.Failed(BackupErrorKind.STORAGE_FULL), env.coordinator.backUpNow())
        assertEquals(BackupErrorKind.STORAGE_FULL, env.coordinator.state.value.lastError)

        env.drive.interceptor = { label, _ -> if (label == "update:$fileId") throw kotlinx.io.IOException("offline") else null }
        assertEquals(BackupOutcome.Retry(BackupErrorKind.NETWORK), env.coordinator.runOnAppOpen())

        assertEquals(local, env.localSnapshot())
        assertEquals(uploadedBefore, env.drive.content(fileId))
        assertEquals(lastSuccess, env.states.load().lastSuccessfulBackupAt)

        env.drive.interceptor = null
        assertEquals(BackupOutcome.Uploaded, env.coordinator.runOnAppOpen())
        assertEquals(local, SnapshotCodec.decode(checkNotNull(env.drive.content(fileId))).withoutTime())
        assertNull(env.coordinator.state.value.lastError)
    }

    @Test
    fun `drive denial other than a full storage is a generic access error`() = coordinatorTest { env ->
        env.connect()
        val fileId = env.drive.fileIds.single()
        env.drive.interceptor = { label, _ ->
            if (label == "update:$fileId") respond(forbidden("domainPolicy"), HttpStatusCode.Forbidden) else null
        }

        assertEquals(BackupOutcome.Failed(BackupErrorKind.FORBIDDEN), env.coordinator.backUpNow())
    }
}
