package app.servicecalendar.ui.backup

import androidx.sqlite.execSQL
import app.servicecalendar.backup.BackupPhase
import app.servicecalendar.backup.BackupStateStore
import app.servicecalendar.backup.ConsentResult
import app.servicecalendar.ui.backup.CoordinatorController
import app.servicecalendar.backup.FakeDriveServer
import app.servicecalendar.backup.FakeGoogleAuthProvider
import app.servicecalendar.backup.BackupCoordinator
import app.servicecalendar.backup.RestoreService
import app.servicecalendar.backup.SnapshotCodec
import app.servicecalendar.backup.sampleSnapshot
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.GatedDispatcher
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The screen model on top of a real [BackupCoordinator], [FakeDriveServer] and [FakeGoogleAuthProvider]. */
class BackupModelCoordinatorTest {
    @Test
    fun `connect with an existing copy then restore through the screen model`() {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val dispatcher = GatedDispatcher()
                val store = DataStore(path, dispatcher)
                val job = Job()
                try {
                    withTimeout(60_000) {
                        val auth = FakeGoogleAuthProvider(account = null)
                        val drive = FakeDriveServer()
                        drive.put(SnapshotCodec.encode(sampleSnapshot()))
                        store.write { it.execSQL("INSERT INTO procedure (name, archived) VALUES ('Локальна', 0)") }
                        val coordinator = BackupCoordinator(
                            auth, drive.client(auth), store, BackupStateStore(store), RestoreService(store),
                        )
                        val scope = CoroutineScope(coroutineContext + job)
                        val model = BackupModel(CoordinatorController(coordinator), scope)
                        val latest = model.state.stateIn(scope, SharingStarted.Eagerly, BackupScreenState.Unavailable)

                        auth.consentResult = ConsentResult.Granted(FakeGoogleAuthProvider.ACCOUNT_A)
                        model.connect()
                        withTimeout(10_000) { latest.first { it.dialog == BackupDialog.Choice } }
                        assertEquals(listOf("list"), drive.log)

                        model.chooseRestore()
                        val warning = withTimeout(10_000) { latest.first { it.dialog is BackupDialog.ConfirmRestore } }
                        val report = assertIs<BackupDialog.ConfirmRestore>(warning.dialog).report
                        assertEquals(1, report.local.procedures)
                        assertEquals(2, report.backup.procedures)

                        model.confirmRestore()
                        val done = withTimeout(10_000) { latest.first { it.notice == BackupNotice.Restored && !it.working } }
                        assertEquals(BackupPhase.ACTIVE, done.backup.phase)
                    }
                } finally {
                    job.cancel()
                    dispatcher.holding = false
                    dispatcher.releaseHeld()
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }
}
