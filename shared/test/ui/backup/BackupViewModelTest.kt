package app.servicecalendar.ui.backup

import app.servicecalendar.backup.BackupBinding
import app.servicecalendar.backup.BackupErrorKind
import app.servicecalendar.backup.BackupOperation
import app.servicecalendar.backup.BackupOutcome
import app.servicecalendar.backup.BackupPhase
import app.servicecalendar.backup.BackupSkipReason
import app.servicecalendar.backup.BackupUiState
import app.servicecalendar.backup.DatasetCounts
import app.servicecalendar.backup.ReplaceConfirmation
import app.servicecalendar.backup.RestoreReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import app.servicecalendar.ui.calendar.localMediumDate
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The backup screen logic without a database, Drive or Compose. The coordinator is replaced by a
 * [FakeController] that behaves like the real one: it sets `busy`, `restorePreview` and `lastError`.
 */
class BackupViewModelTest {
    private val confirmation = ReplaceConfirmation(BackupBinding("account-a", 1), "file-1")

    private val report = RestoreReport(
        createdAt = "2026-11-10T06:00:00Z",
        currencyCode = "BRL",
        backup = DatasetCounts(procedures = 2, places = 2, placeRates = 3, workEntries = 4, workLines = 5),
        local = DatasetCounts(procedures = 1, places = 3, placeRates = 0, workEntries = 11, workLines = 2),
        localCurrencyCode = "BRL",
        differsFromLocal = true,
        localGeneration = 0,
    )

    private class FakeController(initial: BackupUiState) : BackupController {
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<BackupUiState> get() = mutable
        val calls = mutableListOf<String>()
        var confirmation: ReplaceConfirmation? = null
        var prepared: RestoreReport? = null
        var prepareOutcome: BackupOutcome = BackupOutcome.Completed
        var restoreOutcome: BackupOutcome = BackupOutcome.Completed

        /** When set, `restoreCopy` waits for it, simulating a long restore. */
        var restoreGate: CompletableDeferred<Unit>? = null
        var connectGate: CompletableDeferred<Unit>? = null

        override suspend fun refresh() {
            calls += "refresh"
        }

        override suspend fun connectAccount(): BackupOutcome {
            calls += "connect"
            connectGate?.await()
            mutable.value = mutable.value.copy(
                accountLabel = "luiza@example.com",
                phase = BackupPhase.ACTIVE,
                lastError = null,
            )
            return BackupOutcome.Uploaded
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            mutable.value = BackupUiState()
        }

        override suspend fun syncNow(): BackupOutcome {
            calls += "sync"
            // Like the real coordinator: uploads are forbidden while a choice is pending, and any skip clears the error.
            val awaiting = mutable.value.phase == BackupPhase.AWAITING_CHOICE
            mutable.value = mutable.value.copy(lastError = null)
            return if (awaiting) BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE) else BackupOutcome.Uploaded
        }

        var backUpOutcome: BackupOutcome = BackupOutcome.Uploaded

        override suspend fun backUpNow(): BackupOutcome {
            calls += "backUpNow"
            return backUpOutcome
        }

        override suspend fun postpone(): BackupOutcome {
            calls += "postpone"
            mutable.value = mutable.value.copy(choicePostponed = true)
            return BackupOutcome.Completed
        }

        override suspend fun requestReplace(): ReplaceConfirmation? {
            calls += "requestReplace"
            return confirmation
        }

        override suspend fun confirmReplace(confirmation: ReplaceConfirmation): BackupOutcome {
            calls += "confirmReplace:${confirmation.driveFileId}"
            mutable.value = mutable.value.copy(phase = BackupPhase.ACTIVE)
            return BackupOutcome.Uploaded
        }

        override suspend fun prepareRestore(): BackupOutcome {
            calls += "prepareRestore"
            if (prepareOutcome == BackupOutcome.Completed) {
                mutable.value = mutable.value.copy(restorePreview = prepared)
            } else {
                val error = (prepareOutcome as? BackupOutcome.Failed)?.error ?: (prepareOutcome as? BackupOutcome.Retry)?.error
                mutable.value = mutable.value.copy(restorePreview = null, lastError = error)
            }
            return prepareOutcome
        }

        override suspend fun restoreCopy(): BackupOutcome {
            calls += "restoreCopy"
            mutable.value = mutable.value.copy(busy = BackupOperation.RESTORING)
            restoreGate?.await()
            // The prepared copy is consumed either way.
            mutable.value = mutable.value.copy(
                busy = null,
                restorePreview = null,
                phase = if (restoreOutcome == BackupOutcome.Completed) BackupPhase.ACTIVE else mutable.value.phase,
                lastError = (restoreOutcome as? BackupOutcome.Retry)?.error ?: (restoreOutcome as? BackupOutcome.Failed)?.error,
            )
            return restoreOutcome
        }

        override fun cancelRestore() {
            calls += "cancelRestore"
            mutable.value = mutable.value.copy(restorePreview = null)
        }
    }

    private val awaitingChoice = BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.AWAITING_CHOICE)

    private class Harness(val controller: FakeController?, val model: BackupModel, val latest: StateFlow<BackupScreenState>) {
        suspend fun until(condition: (BackupScreenState) -> Boolean): BackupScreenState =
            withTimeout(10_000) { latest.first(condition) }
    }

    private fun screenTest(initial: BackupUiState?, block: suspend Harness.() -> Unit) = runBlocking {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        try {
            withTimeout(30_000) {
                val controller = initial?.let(::FakeController)
                val model = BackupModel(controller, scope)
                val latest = model.state.stateIn(scope, SharingStarted.Eagerly, BackupScreenState(available = false, working = true))
                Harness(controller, model, latest).block()
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `without a controller the screen is unavailable and actions do nothing`() = screenTest(null) {
        val state = until { it == BackupScreenState.Unavailable }
        assertFalse(state.available)
        model.open()
        model.connect()
        model.chooseRestore()
        model.chooseReplace()
        model.confirmReplace()
        model.confirmRestore()
        model.cancelRestore()
        model.requestDisconnect()
        model.confirmDisconnect()
        assertEquals(BackupScreenState.Unavailable, latest.value)
    }

    @Test
    fun `disconnect needs a confirmation and cancelling keeps the account`() = screenTest(
        BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.ACTIVE),
    ) {
        val controller = checkNotNull(controller)
        until { it.available }

        model.confirmDisconnect() // does nothing without an open dialog
        model.requestDisconnect()
        assertEquals(BackupDialog.ConfirmDisconnect, until { it.dialog != null }.dialog)
        assertEquals(emptyList<String>(), controller.calls)

        model.cancelDisconnect()
        val kept = until { it.dialog == null }
        assertEquals(BackupPhase.ACTIVE, kept.backup.phase)
        assertEquals(emptyList<String>(), controller.calls)

        model.requestDisconnect()
        until { it.dialog == BackupDialog.ConfirmDisconnect }
        model.confirmDisconnect()
        val done = until { it.backup.phase == BackupPhase.NOT_CONNECTED && !it.working }
        assertNull(done.dialog)
        assertEquals(listOf("disconnect"), controller.calls)
    }

    @Test
    fun `disconnect confirmation takes priority over the choice and cancelling brings the choice back`() =
        screenTest(awaitingChoice) {
            val controller = checkNotNull(controller)
            until { it.dialog == BackupDialog.Choice }

            model.requestDisconnect()
            until { it.dialog == BackupDialog.ConfirmDisconnect }
            model.cancelDisconnect()
            until { it.dialog == BackupDialog.Choice }
            assertEquals(emptyList<String>(), controller.calls)
        }

    @Test
    fun `a disconnect confirmation left open does not come back after the account is removed and connected again`() =
        screenTest(BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.ACTIVE)) {
            val controller = checkNotNull(controller)
            until { it.available }
            model.requestDisconnect()
            until { it.dialog == BackupDialog.ConfirmDisconnect }

            // The account was removed in system settings.
            controller.mutable.value = BackupUiState()
            until { it.dialog == null && it.backup.phase == BackupPhase.NOT_CONNECTED }

            // After connecting a copy was found: only the choice must show, without the old confirmation.
            controller.mutable.value = awaitingChoice
            assertEquals(BackupDialog.Choice, until { it.dialog != null }.dialog)
            model.cancelDisconnect()
            assertEquals(BackupDialog.Choice, until { it.dialog != null }.dialog)
        }

    @Test
    fun `connect clears a disconnect confirmation left open`() = screenTest(BackupUiState()) {
        val controller = checkNotNull(controller)
        until { it.available }
        model.requestDisconnect() // no dialog is shown when not connected, but the confirmation is remembered
        model.connect()
        until { it.backup.phase == BackupPhase.ACTIVE && !it.working }
        controller.mutable.value = controller.mutable.value.copy(phase = BackupPhase.AWAITING_CHOICE)
        assertEquals(BackupDialog.Choice, until { it.dialog != null }.dialog)
    }

    @Test
    fun `a replace confirmation does not outlive the choice phase`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.confirmation = confirmation
        until { it.dialog == BackupDialog.Choice }
        model.chooseReplace()
        until { it.dialog is BackupDialog.ConfirmReplace }

        controller.mutable.value = controller.mutable.value.copy(phase = BackupPhase.ACTIVE)
        until { it.dialog == null }
        controller.mutable.value = controller.mutable.value.copy(phase = BackupPhase.AWAITING_CHOICE)
        assertEquals(BackupDialog.Choice, until { it.dialog != null }.dialog)
        model.confirmReplace() // a stale confirmation does nothing
        assertEquals(listOf("requestReplace"), controller.calls)
    }

    @Test
    fun `cancelling a replace started from the card does not reopen the dismissed choice`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.confirmation = confirmation
        until { it.dialog == BackupDialog.Choice }
        model.dismissChoice()
        until { it.dialog == null }

        model.chooseReplace()
        until { it.dialog is BackupDialog.ConfirmReplace }
        model.cancelReplace()
        val after = until { it.dialog == null && !it.working }
        assertEquals(BackupPhase.AWAITING_CHOICE, after.backup.phase)
    }

    @Test
    fun `open refreshes and connect shows the account and last backup`() = screenTest(BackupUiState()) {
        val controller = checkNotNull(controller)
        assertEquals(BackupPhase.NOT_CONNECTED, until { it.available }.backup.phase)
        model.open()
        model.connect()
        val state = until { it.backup.phase == BackupPhase.ACTIVE && !it.working }
        assertEquals("luiza@example.com", state.backup.accountLabel)
        assertEquals(listOf("refresh", "connect"), controller.calls)
        assertNull(state.dialog)
    }

    @Test
    fun `last backup text uses the local time zone and a placeholder when absent`() {
        val at = Instant.parse("2026-11-10T12:05:00Z")
        val day = localMediumDate(LocalDate(2026, 11, 10))
        assertEquals("$day, 12:05", BackupStrings.lastBackupText(at, TimeZone.UTC))
        assertEquals("$day, 14:05", BackupStrings.lastBackupText(at, TimeZone.of("Europe/Kyiv")))
        assertEquals(BackupStrings.NO_BACKUP_YET, BackupStrings.lastBackupText(null))
        assertEquals("not a time", BackupStrings.copyCreatedText("not a time"))
    }

    @Test
    fun `counts use English singular and plural forms`() {
        assertEquals("1 procedure", BackupStrings.plural(1, "procedure", "procedures"))
        assertEquals("0 procedures", BackupStrings.plural(0, "procedure", "procedures"))
        assertEquals("2 procedures", BackupStrings.plural(2, "procedure", "procedures"))
        assertEquals("11 procedures", BackupStrings.plural(11, "procedure", "procedures"))
        assertEquals(
            "1 procedure, 3 places, 0 prices, 11 day entries, 2 procedure lines",
            BackupStrings.countsText(report.local),
        )
    }

    @Test
    fun `a found copy opens the choice dialog until dismissed or postponed`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        assertEquals(BackupDialog.Choice, until { it.available }.dialog)

        model.dismissChoice()
        val dismissed = until { it.dialog == null }
        // The choice stays unfinished: the card buttons remain available.
        assertEquals(BackupPhase.AWAITING_CHOICE, dismissed.backup.phase)
        assertFalse(dismissed.blocked)

        model.cancelReplace() // no replace was started from the dialog, so a dismissed choice does not come back
        assertNull(until { true }.dialog)

        model.postpone()
        val postponed = until { it.backup.choicePostponed && !it.working }
        assertNull(postponed.dialog)
        assertEquals(BackupPhase.AWAITING_CHOICE, postponed.backup.phase)
        assertEquals(listOf("postpone"), controller.calls)
    }

    @Test
    fun `replace needs a separate confirmation and cancelling returns to the choice`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.confirmation = confirmation
        until { it.dialog == BackupDialog.Choice }

        model.chooseReplace()
        assertEquals(BackupDialog.ConfirmReplace(confirmation), until { it.dialog is BackupDialog.ConfirmReplace }.dialog)
        assertEquals(listOf("requestReplace"), controller.calls) // nothing is replaced without confirmation

        model.cancelReplace()
        until { it.dialog == BackupDialog.Choice }
        assertEquals(listOf("requestReplace"), controller.calls)

        model.chooseReplace()
        until { it.dialog is BackupDialog.ConfirmReplace }
        model.confirmReplace()
        val done = until { it.backup.phase == BackupPhase.ACTIVE && !it.working }
        assertNull(done.dialog)
        assertEquals(listOf("requestReplace", "requestReplace", "confirmReplace:file-1"), controller.calls)
    }

    @Test
    fun `back up now runs the forced backup and shows a notice`() =
        screenTest(BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.ACTIVE)) {
            val controller = checkNotNull(controller)
            model.backUpNow()
            val state = until { it.notice == BackupNotice.BackupSaved && !it.working }
            assertEquals(listOf("backUpNow"), controller.calls)
            assertNull(state.errorText)
            model.dismissNotice()
            until { it.notice == null }
        }

    @Test
    fun `back up now that is skipped shows no success notice`() =
        screenTest(BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.ACTIVE)) {
            val controller = checkNotNull(controller)
            controller.backUpOutcome = BackupOutcome.Skipped(BackupSkipReason.BUSY)
            model.backUpNow()
            val state = until { !it.working && controller.calls.contains("backUpNow") }
            assertNull(state.notice)
        }

    @Test
    fun `replace that is no longer possible shows a notice instead of a dialog`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.confirmation = null
        until { it.dialog == BackupDialog.Choice }
        model.chooseReplace()
        val state = until { it.notice == BackupNotice.ReplaceUnavailable && !it.working }
        assertNull(state.dialog)
        model.dismissNotice()
        until { it.notice == null }
    }

    @Test
    fun `restore warns with counts then restores and reports success`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepared = report
        until { it.dialog == BackupDialog.Choice }

        model.chooseRestore()
        val warning = until { it.dialog is BackupDialog.ConfirmRestore }
        assertEquals(BackupDialog.ConfirmRestore(report), warning.dialog)
        assertEquals(listOf("prepareRestore"), controller.calls) // the restore has not started yet
        assertEquals(
            "In the backup: 2 procedures, 2 places, 3 prices, 4 day entries, 5 procedure lines",
            BackupStrings.restoreCopyLine(report),
        )
        assertEquals(
            "Will be replaced on this device: 1 procedure, 3 places, 0 prices, 11 day entries, 2 procedure lines",
            BackupStrings.restoreLocalLine(report),
        )

        model.confirmRestore()
        val done = until { it.notice == BackupNotice.Restored && !it.working }
        assertNull(done.dialog)
        assertEquals(BackupPhase.ACTIVE, done.backup.phase)
        assertEquals(listOf("prepareRestore", "restoreCopy"), controller.calls)
    }

    @Test
    fun `cancelling the restore warning changes nothing and brings the choice back`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepared = report
        until { it.dialog == BackupDialog.Choice }
        model.chooseRestore()
        until { it.dialog is BackupDialog.ConfirmRestore }

        model.cancelRestore()
        val state = until { it.dialog == BackupDialog.Choice }
        assertNull(state.backup.restorePreview)
        assertEquals(listOf("prepareRestore", "cancelRestore"), controller.calls)
    }

    @Test
    fun `restore warning survives recreating the screen model`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.mutable.value = controller.mutable.value.copy(restorePreview = report)
        // A new model over the same coordinator (screen rotation): the dialog comes from the coordinator state.
        val job = Job()
        try {
            val second = BackupModel(controller, CoroutineScope(kotlin.coroutines.coroutineContext + job))
            val state = withTimeout(10_000) { second.state.first { it.dialog is BackupDialog.ConfirmRestore } }
            assertEquals(BackupDialog.ConfirmRestore(report), state.dialog)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `restoring blocks every action and hides dialogs`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepared = report
        val gate = CompletableDeferred<Unit>()
        controller.restoreGate = gate
        until { it.dialog == BackupDialog.Choice }
        model.chooseRestore()
        until { it.dialog is BackupDialog.ConfirmRestore }

        model.confirmRestore()
        val restoring = until { it.restoring }
        assertTrue(restoring.blocked)
        assertNull(restoring.dialog)

        val before = controller.calls.toList()
        model.connect()
        model.chooseReplace()
        model.postpone()
        model.retry(BackupRetry.Sync)
        model.chooseRestore()
        model.confirmRestore()
        assertEquals(before, controller.calls)

        gate.complete(Unit)
        until { it.notice == BackupNotice.Restored && !it.blocked }
    }

    @Test
    fun `a double tap starts the action once`() = screenTest(BackupUiState()) {
        val controller = checkNotNull(controller)
        val gate = CompletableDeferred<Unit>()
        controller.connectGate = gate
        until { it.available }
        model.connect()
        model.connect()
        until { it.working }
        model.connect()
        gate.complete(Unit)
        until { it.backup.phase == BackupPhase.ACTIVE && !it.working }
        assertEquals(listOf("connect"), controller.calls)
    }

    @Test
    fun `a stale prepared copy asks to prepare again`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepared = report
        until { it.dialog == BackupDialog.Choice }
        model.chooseRestore()
        until { it.dialog is BackupDialog.ConfirmRestore }
        controller.restoreOutcome = BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE)

        model.confirmRestore()
        val state = until { it.notice == BackupNotice.RestoreStale && !it.working }
        assertEquals(BackupPhase.AWAITING_CHOICE, state.backup.phase)
    }

    @Test
    fun `an invalid copy shows the reason and offers to check the copy again`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepareOutcome = BackupOutcome.Failed(BackupErrorKind.INVALID_COPY)
        until { it.dialog == BackupDialog.Choice }

        model.chooseRestore()
        val failed = until { it.backup.lastError == BackupErrorKind.INVALID_COPY && !it.working }
        assertNull(failed.dialog) // the error is visible and the choice dialog does not cover it
        assertEquals(BackupRetry.PrepareRestore, failed.retry)
        assertEquals(BackupStrings.errorText(BackupErrorKind.INVALID_COPY), failed.errorText)
        assertTrue("not restored" in checkNotNull(failed.errorText))

        controller.prepareOutcome = BackupOutcome.Completed
        controller.prepared = report
        model.retry(BackupRetry.PrepareRestore)
        until { it.dialog is BackupDialog.ConfirmRestore }
    }

    @Test
    fun `an invalid copy from an upload does not say it was not restored`() {
        val restore = BackupScreenState(true, BackupUiState(phase = BackupPhase.AWAITING_CHOICE, lastError = BackupErrorKind.INVALID_COPY))
        val upload = BackupScreenState(true, BackupUiState(phase = BackupPhase.ACTIVE, lastError = BackupErrorKind.INVALID_COPY))

        assertEquals(BackupStrings.INVALID_RESTORE, restore.errorText)
        assertEquals(BackupStrings.INVALID_UPLOAD, upload.errorText)
        assertTrue("not restored" !in checkNotNull(upload.errorText) && "not saved" in checkNotNull(upload.errorText))
    }

    @Test
    fun `back up now failures name the cause and say the local data is safe`() {
        assertEquals("Backup saved to Google Drive.", BackupStrings.NOTICE_BACKUP_SAVED)
        val full = BackupStrings.errorText(BackupErrorKind.STORAGE_FULL)
        assertTrue("storage is full" in full && "data on the device is safe" in full, full)
        val denied = BackupStrings.errorText(BackupErrorKind.FORBIDDEN)
        assertTrue("denied access" in denied && "data on the device is safe" in denied && "storage" !in denied, denied)
        assertTrue("data on the device is safe" in BackupStrings.errorText(BackupErrorKind.NETWORK))
        for (kind in BackupErrorKind.entries) assertTrue(BackupStrings.errorText(kind).isNotBlank(), kind.name)
        assertEquals(BackupRetry.Sync, BackupScreenState(true, BackupUiState(phase = BackupPhase.ACTIVE, lastError = BackupErrorKind.STORAGE_FULL)).retry)
    }

    @Test
    fun `retry action depends on the error and phase`() {
        fun retry(phase: BackupPhase, error: BackupErrorKind?) =
            BackupScreenState(true, BackupUiState(phase = phase, lastError = error)).retry

        assertNull(retry(BackupPhase.ACTIVE, null))
        assertEquals(BackupRetry.Sync, retry(BackupPhase.SEARCH_FAILED, BackupErrorKind.NETWORK))
        assertEquals(BackupRetry.Sync, retry(BackupPhase.ACTIVE, BackupErrorKind.DRIVE_UNAVAILABLE))
        assertEquals(BackupRetry.Reconnect, retry(BackupPhase.ACTIVE, BackupErrorKind.NEEDS_USER_ACTION))
        assertEquals(BackupRetry.Reconnect, retry(BackupPhase.SEARCH_REQUIRED, BackupErrorKind.AUTH_FAILED))
        assertEquals(BackupRetry.PrepareRestore, retry(BackupPhase.AWAITING_CHOICE, BackupErrorKind.INVALID_COPY))
        assertEquals(BackupRetry.Sync, retry(BackupPhase.ACTIVE, BackupErrorKind.INVALID_COPY))
        // Without an account the Connect button is enough.
        assertNull(retry(BackupPhase.NOT_CONNECTED, BackupErrorKind.AUTH_FAILED))
    }

    @Test
    fun `retry runs the matching coordinator call and clears the error`() = screenTest(
        BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.SEARCH_FAILED, lastError = BackupErrorKind.NETWORK),
    ) {
        val controller = checkNotNull(controller)
        val failed = until { it.available }
        assertEquals(BackupRetry.Sync, failed.retry)
        assertTrue(checkNotNull(failed.errorText).isNotBlank())

        model.retry(BackupRetry.Sync)
        val state = until { it.backup.lastError == null && !it.working }
        assertNull(state.errorText)
        assertEquals(listOf("sync"), controller.calls)
    }

    @Test
    fun `retry after a failed prepare in the choice re-prepares instead of syncing`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepareOutcome = BackupOutcome.Retry(BackupErrorKind.NETWORK)
        until { it.dialog == BackupDialog.Choice }
        model.chooseRestore()
        val failed = until { it.backup.lastError == BackupErrorKind.NETWORK && !it.working }
        assertEquals(BackupRetry.PrepareRestore, failed.retry)

        controller.prepareOutcome = BackupOutcome.Completed
        controller.prepared = report
        model.retry(checkNotNull(failed.retry))
        until { it.dialog is BackupDialog.ConfirmRestore }
        assertEquals(listOf("prepareRestore", "prepareRestore"), controller.calls.filter { it != "refresh" })
    }

    @Test
    fun `retry after a failed restore prepares the copy again`() = screenTest(awaitingChoice) {
        val controller = checkNotNull(controller)
        controller.prepared = report
        until { it.dialog == BackupDialog.Choice }
        model.chooseRestore()
        until { it.dialog is BackupDialog.ConfirmRestore }
        controller.restoreOutcome = BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY)

        model.confirmRestore()
        val failed = until { it.backup.lastError == BackupErrorKind.STORAGE_BUSY && !it.working }
        assertNull(failed.dialog) // the prepared copy was consumed
        assertEquals(BackupRetry.PrepareRestore, failed.retry)

        controller.restoreOutcome = BackupOutcome.Completed
        model.retry(checkNotNull(failed.retry))
        until { it.dialog is BackupDialog.ConfirmRestore }
        model.confirmRestore()
        until { it.notice == BackupNotice.Restored && !it.blocked }
        assertEquals(
            listOf("prepareRestore", "restoreCopy", "prepareRestore", "restoreCopy"),
            controller.calls.filter { it != "refresh" },
        )
    }

    @Test
    fun `a failed search without an in-memory error offers a manual retry`() = screenTest(
        BackupUiState(accountLabel = "luiza@example.com", phase = BackupPhase.SEARCH_FAILED),
    ) {
        val controller = checkNotNull(controller)
        val state = until { it.available }
        assertNull(state.errorText)
        assertNull(state.retry)
        assertEquals(BackupRetry.Sync, state.searchRetry)
        assertEquals(
            BackupRetry.Sync,
            BackupScreenState(true, BackupUiState(phase = BackupPhase.SEARCH_REQUIRED)).searchRetry,
        )
        assertNull(BackupScreenState(true, BackupUiState(phase = BackupPhase.ACTIVE)).searchRetry)
        assertNull(
            BackupScreenState(true, BackupUiState(phase = BackupPhase.SEARCH_FAILED, lastError = BackupErrorKind.NETWORK)).searchRetry,
        )

        model.retry(checkNotNull(state.searchRetry))
        until { !it.working && controller.calls.contains("sync") }
        assertEquals(listOf("sync"), controller.calls)
    }
}
