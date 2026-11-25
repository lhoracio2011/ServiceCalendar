package app.servicecalendar.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.servicecalendar.AppContainer
import app.servicecalendar.backup.BackupCoordinator
import app.servicecalendar.backup.BackupErrorKind
import app.servicecalendar.backup.BackupOperation
import app.servicecalendar.backup.BackupOutcome
import app.servicecalendar.backup.BackupPhase
import app.servicecalendar.backup.BackupSkipReason
import app.servicecalendar.backup.BackupUiState
import app.servicecalendar.backup.ReplaceConfirmation
import app.servicecalendar.backup.RestoreReport
import app.servicecalendar.backup.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface BackupController {
    val state: StateFlow<BackupUiState>

    suspend fun refresh()

    suspend fun connectAccount(): BackupOutcome

    suspend fun disconnect()

    suspend fun syncNow(): BackupOutcome

    suspend fun backUpNow(): BackupOutcome

    suspend fun postpone(): BackupOutcome

    suspend fun requestReplace(): ReplaceConfirmation?

    suspend fun confirmReplace(confirmation: ReplaceConfirmation): BackupOutcome

    suspend fun prepareRestore(): BackupOutcome

    suspend fun restoreCopy(): BackupOutcome

    fun cancelRestore()
}

class CoordinatorController(private val coordinator: BackupCoordinator) : BackupController {
    override val state: StateFlow<BackupUiState> get() = coordinator.state

    override suspend fun refresh() = coordinator.refresh()

    override suspend fun connectAccount() = coordinator.connectAccount()

    override suspend fun disconnect() = coordinator.disconnect()

    override suspend fun syncNow() = coordinator.syncNow()

    override suspend fun backUpNow() = coordinator.backUpNow()

    override suspend fun postpone() = coordinator.postpone()

    override suspend fun requestReplace() = coordinator.requestReplace()

    override suspend fun confirmReplace(confirmation: ReplaceConfirmation) = coordinator.confirmReplace(confirmation)

    override suspend fun prepareRestore() = coordinator.prepareRestore()

    override suspend fun restoreCopy() = coordinator.restoreCopy()

    override fun cancelRestore() = coordinator.cancelRestore()
}

sealed interface BackupDialog {
    data object Choice : BackupDialog

    data class ConfirmReplace(val confirmation: ReplaceConfirmation) : BackupDialog

    data class ConfirmRestore(val report: RestoreReport) : BackupDialog

    data object ConfirmDisconnect : BackupDialog
}

enum class BackupNotice {
    Restored,

    RestoreStale,

    ReplaceUnavailable,

    BackupSaved,
}

enum class BackupRetry {
    Reconnect,

    PrepareRestore,

    Sync,
}

data class BackupScreenState(
    val available: Boolean,
    val backup: BackupUiState = BackupUiState(),
    val dialog: BackupDialog? = null,
    val notice: BackupNotice? = null,
    val working: Boolean = false,
) {
    val restoring: Boolean get() = backup.busy == BackupOperation.RESTORING

    val blocked: Boolean get() = working || backup.isBusy

    val retry: BackupRetry?
        get() {
            val error = backup.lastError ?: return null
            if (backup.phase == BackupPhase.NOT_CONNECTED) return null
            return when {
                error == BackupErrorKind.NEEDS_USER_ACTION || error == BackupErrorKind.AUTH_FAILED -> BackupRetry.Reconnect
                backup.phase == BackupPhase.AWAITING_CHOICE -> BackupRetry.PrepareRestore
                else -> BackupRetry.Sync
            }
        }

    val searchRetry: BackupRetry?
        get() {
            if (backup.lastError != null) return null
            return if (backup.phase == BackupPhase.SEARCH_REQUIRED || backup.phase == BackupPhase.SEARCH_FAILED) {
                BackupRetry.Sync
            } else {
                null
            }
        }

    val errorText: String?
        get() = backup.lastError?.let {
            // A rejected copy is only seen before the choice is made; in the active phase the same error comes from an upload.
            BackupStrings.errorText(it, backup.invalidCopyReason?.userMessage(), uploading = backup.phase == BackupPhase.ACTIVE)
        }

    companion object {
        val Unavailable = BackupScreenState(available = false)
    }
}

class BackupModel(
    private val controller: BackupController?,
    private val scope: CoroutineScope,
) {
    private val confirmDialog = MutableStateFlow<BackupDialog?>(null)
    private val choiceDismissed = MutableStateFlow(false)
    private val notice = MutableStateFlow<BackupNotice?>(null)
    private val working = MutableStateFlow(false)

    private var confirmReplaceAccount: String? = null

    init {
        if (controller != null) {
            scope.launch { controller.state.collect { dropStaleConfirm(it) } }
        }
    }

    val state: Flow<BackupScreenState> =
        if (controller == null) {
            flowOf(BackupScreenState.Unavailable)
        } else {
            combine(controller.state, confirmDialog, choiceDismissed, notice, working) { backup, confirm, dismissed, note, busy ->
                BackupScreenState(
                    available = true,
                    backup = backup,
                    dialog = dialogFor(backup, confirm, dismissed, busy),
                    notice = note,
                    working = busy,
                )
            }
        }

    fun open() {
        val controller = controller ?: return
        scope.launch { controller.refresh() }
    }

    fun connect() = act { controller ->
        confirmDialog.value = null
        choiceDismissed.value = false
        controller.connectAccount()
    }

    fun requestDisconnect() {
        val controller = controller ?: return
        if (working.value || controller.state.value.isBusy) return
        confirmDialog.value = BackupDialog.ConfirmDisconnect
    }

    fun confirmDisconnect() {
        if (confirmDialog.value != BackupDialog.ConfirmDisconnect) return
        act { controller ->
            confirmDialog.value = null
            choiceDismissed.value = false
            controller.disconnect()
            null
        }
    }

    fun cancelDisconnect() {
        if (confirmDialog.value == BackupDialog.ConfirmDisconnect) confirmDialog.value = null
    }

    fun retry(kind: BackupRetry) = act { controller ->
        when (kind) {
            BackupRetry.Reconnect -> controller.connectAccount()
            // syncNow does nothing while a choice is pending, so retry performs the real action (preparing the copy).
            BackupRetry.PrepareRestore -> prepareRestore(controller)
            BackupRetry.Sync -> controller.syncNow()
        }
    }

    fun backUpNow() = act { controller ->
        val outcome = controller.backUpNow()
        if (outcome == BackupOutcome.Uploaded) notice.value = BackupNotice.BackupSaved
        outcome
    }

    fun postpone() = act { controller ->
        choiceDismissed.value = false
        controller.postpone()
    }

    fun chooseRestore() = act { controller -> prepareRestore(controller) }

    fun dismissChoice() {
        choiceDismissed.value = true
    }

    fun chooseReplace() = act { controller ->
        val confirmation = controller.requestReplace()
        if (confirmation == null) {
            choiceDismissed.value = true
            notice.value = BackupNotice.ReplaceUnavailable
        } else {
            confirmReplaceAccount = controller.state.value.accountLabel
            confirmDialog.value = BackupDialog.ConfirmReplace(confirmation)
        }
        null
    }

    fun confirmReplace() {
        val dialog = confirmDialog.value as? BackupDialog.ConfirmReplace ?: return
        act { controller ->
            confirmDialog.value = null
            controller.confirmReplace(dialog.confirmation)
        }
    }

    fun cancelReplace() {
        if (confirmDialog.value is BackupDialog.ConfirmReplace) confirmDialog.value = null
    }

    fun confirmRestore() = act { controller ->
        val outcome = controller.restoreCopy()
        notice.value = when {
            outcome == BackupOutcome.Completed -> BackupNotice.Restored
            outcome is BackupOutcome.Skipped && outcome.reason == BackupSkipReason.NOT_APPLICABLE -> BackupNotice.RestoreStale
            else -> null
        }
        null
    }

    fun cancelRestore() {
        controller?.cancelRestore()
        choiceDismissed.value = false
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun dropStaleConfirm(backup: BackupUiState) {
        when (val confirm = confirmDialog.value) {
            BackupDialog.ConfirmDisconnect ->
                if (backup.phase == BackupPhase.NOT_CONNECTED) {
                    confirmDialog.compareAndSet(confirm, null)
                }

            is BackupDialog.ConfirmReplace ->
                if (backup.phase != BackupPhase.AWAITING_CHOICE || backup.accountLabel != confirmReplaceAccount) {
                    confirmDialog.compareAndSet(confirm, null)
                }

            else -> Unit
        }
    }

    private suspend fun prepareRestore(controller: BackupController): BackupOutcome {
        choiceDismissed.value = true
        val outcome = controller.prepareRestore()
        if (outcome is BackupOutcome.Skipped && outcome.reason == BackupSkipReason.NOT_APPLICABLE) {
            notice.value = BackupNotice.RestoreStale
        }
        return outcome
    }

    private fun act(block: suspend (BackupController) -> BackupOutcome?) {
        val controller = controller ?: return
        if (controller.state.value.isBusy) return
        if (!working.compareAndSet(expect = false, update = true)) return
        notice.value = null
        scope.launch {
            try {
                block(controller)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                working.value = false
            }
        }
    }

    /** Only hides inapplicable dialogs; the stored confirmation is cleared elsewhere so it cannot reappear later. */
    private fun dialogFor(
        backup: BackupUiState,
        confirm: BackupDialog?,
        choiceDismissed: Boolean,
        working: Boolean,
    ): BackupDialog? {
        if (working || backup.isBusy) return null
        backup.restorePreview?.let { return BackupDialog.ConfirmRestore(it) }
        if (confirm == BackupDialog.ConfirmDisconnect) {
            return if (backup.phase != BackupPhase.NOT_CONNECTED) confirm else null
        }
        if (backup.phase != BackupPhase.AWAITING_CHOICE) return null
        (confirm as? BackupDialog.ConfirmReplace)?.let { return it }
        return if (backup.choicePostponed || choiceDismissed) null else BackupDialog.Choice
    }
}

private fun defaultController(): BackupController? {
    val container = AppContainer.instance
    return if (container.hasGoogleAuthProvider) CoordinatorController(container.backupCoordinator) else null
}

class BackupViewModel(
    controller: BackupController? = defaultController(),
) : ViewModel() {
    internal val model = BackupModel(controller, viewModelScope)

    val state: StateFlow<BackupScreenState> = model.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = if (controller == null) BackupScreenState.Unavailable else BackupScreenState(available = true),
    )

    init {
        model.open()
    }
}
