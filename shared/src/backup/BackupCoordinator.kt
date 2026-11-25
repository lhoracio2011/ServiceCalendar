package app.servicecalendar.backup

import app.servicecalendar.data.DataStore
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.StorageBusy
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

enum class BackupPhase {
    NOT_CONNECTED,

    SEARCH_REQUIRED,

    SEARCH_FAILED,

    AWAITING_CHOICE,

    ACTIVE,
}

enum class BackupOperation {
    CHECKING,

    SEARCHING,

    UPLOADING,

    PREPARING_RESTORE,

    RESTORING,
}

enum class BackupErrorKind {
    NETWORK,

    DRIVE_UNAVAILABLE,

    NEEDS_USER_ACTION,

    AUTH_FAILED,

    FORBIDDEN,

    STORAGE_FULL,

    STORAGE_BUSY,

    INVALID_COPY,

    TOO_LARGE,

    COPY_MISSING,

    UNEXPECTED,
}

enum class BackupSkipReason {
    NOT_CONNECTED,

    AWAITING_CHOICE,

    UP_TO_DATE,

    BUSY,

    RESTORING,

    CANCELLED,

    CONSENT_IN_PROGRESS,

    NOT_APPLICABLE,
}

sealed class BackupOutcome {
    open val shouldRetry: Boolean get() = false

    data object Uploaded : BackupOutcome()

    data object Completed : BackupOutcome()

    data class Skipped(val reason: BackupSkipReason) : BackupOutcome()

    data object NeedsUserAction : BackupOutcome()

    data class Retry(val error: BackupErrorKind) : BackupOutcome() {
        override val shouldRetry: Boolean get() = true
    }

    data class Failed(val error: BackupErrorKind) : BackupOutcome()
}

data class BackupUiState(
    val accountLabel: String? = null,
    val phase: BackupPhase = BackupPhase.NOT_CONNECTED,
    val choicePostponed: Boolean = false,
    val lastSuccessfulBackupAt: Instant? = null,
    val busy: BackupOperation? = null,
    val lastError: BackupErrorKind? = null,
    val invalidCopyReason: SnapshotInvalidReason? = null,
    val restorePreview: RestoreReport? = null,
) {
    val isBusy: Boolean get() = busy != null

    override fun toString(): String =
        "BackupUiState(account=${if (accountLabel == null) "none" else "***"}, phase=$phase, " +
            "postponed=$choicePostponed, lastSuccess=$lastSuccessfulBackupAt, busy=$busy, lastError=$lastError, " +
            "preview=${restorePreview != null})"
}

/**
 * All Drive and restore operations run under one Mutex. A request arriving while one is running is rejected with Skipped(BUSY) rather than queued, since a queued intent could be stale by the time it runs.
 * Every operation pins a BackupBinding(accountId, dataGeneration); state is written only if the generation is unchanged.
 * A search always precedes the initial upload, and an update is preceded by a metadata check so another device's newer copy is never overwritten.
 * There is no conditional write (If-Match) for Drive content, so a write by another device between check and upload remains a small residual risk.
 * Background runs never show UI; consent is requested only by [connectAccount].
 */
@OptIn(ExperimentalAtomicApi::class)
class BackupCoordinator(
    private val auth: GoogleAuthProvider,
    private val drive: DriveBackupClient,
    private val store: DataStore,
    private val states: BackupStateStore,
    private val restoreService: RestoreService,
    private val now: () -> Instant = { Clock.System.now() },
    private val minBackupInterval: Duration = 24.hours,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(BackupUiState())
    private val prepared = AtomicReference<PreparedRestore?>(null)
    private val consentInProgress = AtomicBoolean(false)

    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    val isConsentInProgress: Boolean get() = consentInProgress.load()

    private class PreparedRestore(
        val binding: BackupBinding,
        val driveFileId: String,
        val remoteRevision: String?,
        val snapshot: BackupSnapshot,
        val report: RestoreReport,
    )

    private class Context(val account: GoogleAccount, val binding: BackupBinding)

    suspend fun refresh() {
        publish(outcome = null)
    }

    /** While consent is pending, automatic runs are skipped. After consent this waits for an in-flight operation instead of returning BUSY, so the consent result is not lost. */
    suspend fun connectAccount(): BackupOutcome {
        if (mutex.isLocked) return BackupOutcome.Skipped(BackupSkipReason.BUSY)
        if (!consentInProgress.compareAndSet(expectedValue = false, newValue = true)) {
            return BackupOutcome.Skipped(BackupSkipReason.BUSY)
        }
        try {
            val account = when (val consent = auth.requestConsent()) {
                is ConsentResult.Granted -> consent.account
                ConsentResult.Cancelled -> return BackupOutcome.Skipped(BackupSkipReason.CANCELLED).also { publish(null) }
                is ConsentResult.Failure -> return authFailure(consent.retryable).also { publish(it) }
            }
            return exclusive(BackupOperation.CHECKING, wait = true, onLocked = { consentInProgress.store(false) }) {
                synchronize(account)
            }
        } finally {
            consentInProgress.store(false)
        }
    }

    suspend fun disconnect() {
        prepared.store(null)
        auth.signOut()
        publish(outcome = null)
    }

    suspend fun syncNow(): BackupOutcome = runAutomatic()

    /** User-requested backup: like [syncNow] but ignores the "updated recently" check. All other guards apply. */
    suspend fun backUpNow(): BackupOutcome = runAutomatic(force = true)

    suspend fun postpone(): BackupOutcome = guarded {
        val context = capture() ?: return@guarded BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED)
        transition(context.binding) { BackupGuard.postpone(it, context.binding) }
        BackupOutcome.Completed
    }

    suspend fun requestReplace(): ReplaceConfirmation? =
        try {
            val context = capture() ?: return null
            BackupGuard.requestReplace(states.load(), context.binding)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    suspend fun confirmReplace(confirmation: ReplaceConfirmation): BackupOutcome {
        val confirmed = guarded {
            val context = capture() ?: return@guarded BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED)
            transition(context.binding) { BackupGuard.confirmReplace(it, context.binding, confirmation) }
            BackupOutcome.Completed
        }
        if (confirmed != BackupOutcome.Completed) return confirmed
        // The user asked to overwrite the copy now: a backup written by this device a few hours ago must not postpone it.
        return runAutomatic(force = true)
    }

    suspend fun prepareRestore(): BackupOutcome = exclusive(BackupOperation.PREPARING_RESTORE) {
        prepared.store(null)
        val context = capture() ?: return@exclusive BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED)
        val fileId = knownCopy(states.load(), context.binding)
            ?: return@exclusive BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE)
        // Fetch the revision marker before the content: if the copy changes in between, the marker is older than the restored data and the next update asks the user instead of overwriting.
        val remoteRevision = when (val meta = drive.metadata(fileId, context.binding.accountId)) {
            is DriveResult.Success -> meta.value.revisionMarker
            is DriveResult.Failure -> {
                if (meta.error == DriveError.NotFound) {
                    transition(context.binding) { BackupGuard.onCopyMissing(it, context.binding) }
                    return@exclusive BackupOutcome.Failed(BackupErrorKind.COPY_MISSING)
                }
                return@exclusive driveFailure(meta.error)
            }
        }
        val json = when (val downloaded = drive.download(fileId, context.binding.accountId)) {
            is DriveResult.Success -> downloaded.value
            is DriveResult.Failure -> {
                if (downloaded.error == DriveError.NotFound) {
                    transition(context.binding) { BackupGuard.onCopyMissing(it, context.binding) }
                    return@exclusive BackupOutcome.Failed(BackupErrorKind.COPY_MISSING)
                }
                if (downloaded.error == DriveError.TooLarge) {
                    return@exclusive BackupOutcome.Failed(BackupErrorKind.TOO_LARGE)
                }
                return@exclusive driveFailure(downloaded.error)
            }
        }
        when (val validation = parseAndValidate(json)) {
            is SnapshotValidationResult.Invalid -> {
                _state.update { it.copy(invalidCopyReason = validation.reason) }
                BackupOutcome.Failed(BackupErrorKind.INVALID_COPY)
            }
            is SnapshotValidationResult.Valid -> {
                val report = restoreService.inspect(validation.snapshot)
                prepared.store(PreparedRestore(context.binding, fileId, remoteRevision, validation.snapshot, report))
                BackupOutcome.Completed
            }
        }
    }

    suspend fun restoreCopy(): BackupOutcome = exclusive(BackupOperation.RESTORING) {
        val ready = prepared.exchange(null) ?: return@exclusive BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE)
        val context = capture()
        if (context == null || context.binding != ready.binding ||
            knownCopy(states.load(), context.binding) != ready.driveFileId
        ) {
            return@exclusive BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE)
        }
        val copyCreatedAt = runCatching { Instant.parse(ready.snapshot.createdAt) }.getOrNull()
        restoreService.restore(ready.snapshot) { generation ->
            BackupGuard.onRestoreSucceeded(
                BackupBinding(context.account.id, generation),
                ready.driveFileId,
                copyCreatedAt,
                ready.remoteRevision,
            )
        }
        BackupOutcome.Completed
    }

    fun cancelRestore() {
        prepared.store(null)
        _state.update { it.copy(restorePreview = null) }
    }

    suspend fun runScheduled(): BackupOutcome = runAutomatic()

    suspend fun runOnAppOpen(): BackupOutcome = runAutomatic()

    /** The app went to the background: uploads only when the data changed since the last backup. Every guard of the automatic run applies. */
    suspend fun runOnLeave(): BackupOutcome = runAutomatic()

    /** Cheap pre-check for callers that would otherwise start platform work for nothing: an account is signed in and the data differs from the last backup. The automatic run applies all other guards. */
    suspend fun hasChangesToBackUp(): Boolean =
        try {
            auth.currentAccount() != null && states.dataChangedSinceBackup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }

    private suspend fun runAutomatic(force: Boolean = false): BackupOutcome {
        if (consentInProgress.load()) return BackupOutcome.Skipped(BackupSkipReason.CONSENT_IN_PROGRESS)
        return exclusive(BackupOperation.CHECKING) {
            val account = auth.currentAccount() ?: return@exclusive BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED)
            synchronize(account, force)
        }
    }

    private suspend fun synchronize(account: GoogleAccount, force: Boolean = false): BackupOutcome {
        val context = Context(account, BackupBinding(account.id, states.loadWithGeneration().dataGeneration))
        val binding = context.binding
        transition(binding) { BackupGuard.bind(it, binding) }
        var searched = false
        repeat(MAX_STEPS) {
            val (state, generation) = states.loadWithGeneration()
            // The data set was replaced since this operation started; retry with the new generation.
            if (generation != binding.dataGeneration) return BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY)
            when (val action = BackupGuard.nextAction(state, binding)) {
                BackupAction.ConnectAccount -> return BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED)
                is BackupAction.AwaitChoice -> return BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE)
                BackupAction.Search -> {
                    search(binding)?.let { return it }
                    searched = true
                }
                BackupAction.CreateInitialCopy -> {
                    if (!searched) {
                        search(binding)?.let { return it }
                        searched = true
                    } else {
                        return upload(binding, driveFileId = null) ?: BackupOutcome.Retry(BackupErrorKind.DRIVE_UNAVAILABLE)
                    }
                }
                is BackupAction.UpdateCopy -> {
                    // Within the daily interval only unchanged data is skipped: an edit (a new price, say) is uploaded by the next run.
                    if (!force && isFresh(state.lastSuccessfulBackupAt) && !states.dataChangedSinceBackup()) {
                        return BackupOutcome.Skipped(BackupSkipReason.UP_TO_DATE)
                    }
                    upload(binding, action.driveFileId)?.let { return it }
                }
            }
        }
        return BackupOutcome.Retry(BackupErrorKind.UNEXPECTED)
    }

    private suspend fun search(binding: BackupBinding): BackupOutcome? {
        setBusy(BackupOperation.SEARCHING)
        transition(binding) { BackupGuard.onSearchStarted(it, binding) }
        val files = when (val listed = drive.list(binding.accountId)) {
            is DriveResult.Success -> listed.value
            is DriveResult.Failure -> return searchFailure(binding, listed.error)
        }
        if (files.isEmpty()) {
            transition(binding) { BackupGuard.onSearchFoundNone(it, binding) }
            return null
        }
        val chosen = if (files.size == 1) {
            files.single()
        } else {
            when (val newest = drive.findNewestValidCopy(files, binding.accountId)) {
                is DriveResult.Success -> newest.value?.file ?: files.first()
                is DriveResult.Failure -> return searchFailure(binding, newest.error)
            }
        }
        transition(binding) { BackupGuard.onSearchFoundCopy(it, binding, chosen.id, chosen.revisionMarker) }
        return null
    }

    private suspend fun searchFailure(binding: BackupBinding, error: DriveError): BackupOutcome {
        if (error != DriveError.AccountChanged) transition(binding) { BackupGuard.onSearchFailed(it, binding) }
        return driveFailure(error)
    }

    private suspend fun upload(binding: BackupBinding, driveFileId: String?): BackupOutcome? {
        setBusy(BackupOperation.UPLOADING)
        if (store.isRestoring.value) return BackupOutcome.Skipped(BackupSkipReason.RESTORING)
        val (state, generation) = states.loadWithGeneration()
        if (generation != binding.dataGeneration || !BackupGuard.canUpload(state, binding)) {
            return BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE)
        }
        val snapshot = SnapshotCodec.readSnapshot(store, utcTimestamp(now()))
        if (store.currentGeneration() != binding.dataGeneration) return BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY)
        if (validateSnapshot(snapshot) is SnapshotValidationResult.Invalid) return BackupOutcome.Failed(BackupErrorKind.INVALID_COPY)
        val json = SnapshotCodec.encode(snapshot)

        val result = if (driveFileId == null) {
            drive.create(json, binding.accountId)
        } else {
            when (val remote = drive.metadata(driveFileId, binding.accountId)) {
                is DriveResult.Success -> if (!BackupGuard.isRemoteCopyUnchanged(state, remote.value)) {
                    transition(binding) { BackupGuard.onCopyChangedRemotely(it, binding, remote.value) }
                    return null
                }
                is DriveResult.Failure -> return when (remote.error) {
                    DriveError.NotFound -> {
                        transition(binding) { BackupGuard.onCopyMissing(it, binding) }
                        null
                    }
                    else -> driveFailure(remote.error)
                }
            }
            drive.update(driveFileId, json, binding.accountId)
        }
        return when (result) {
            is DriveResult.Success -> {
                val at = now()
                val uploaded = result.value
                val saved = states.recordUpload(SnapshotCodec.dataFingerprint(snapshot)) { state, generation ->
                    if (generation == binding.dataGeneration) {
                        BackupGuard.onUploadSucceeded(state, binding, uploaded.id, at, uploaded.revisionMarker)
                    } else {
                        state
                    }
                }
                if (saved.lastSuccessfulBackupAt == at && saved.driveFileId == uploaded.id) {
                    BackupOutcome.Uploaded
                } else {
                    BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY)
                }
            }
            is DriveResult.Failure -> when {
                result.error == DriveError.NotFound && driveFileId != null -> {
                    transition(binding) { BackupGuard.onCopyMissing(it, binding) }
                    null
                }
                result.error == DriveError.TooLarge -> BackupOutcome.Failed(BackupErrorKind.TOO_LARGE)
                else -> driveFailure(result.error)
            }
        }
    }

    private fun isFresh(lastSuccess: Instant?): Boolean {
        if (lastSuccess == null) return false
        // A timestamp in the future (clock moved back) must not block backups.
        val elapsed = now() - lastSuccess
        return elapsed >= Duration.ZERO && elapsed < minBackupInterval
    }

    private fun knownCopy(state: BackupState, binding: BackupBinding): String? =
        when (val action = BackupGuard.nextAction(state, binding)) {
            is BackupAction.AwaitChoice -> action.driveFileId
            is BackupAction.UpdateCopy -> action.driveFileId
            else -> null
        }

    private suspend fun capture(): Context? {
        val account = auth.currentAccount() ?: return null
        return Context(account, BackupBinding(account.id, states.loadWithGeneration().dataGeneration))
    }

    private suspend fun transition(binding: BackupBinding, change: (BackupState) -> BackupState): BackupState =
        states.update { state, generation -> if (generation == binding.dataGeneration) change(state) else state }

    private fun driveFailure(error: DriveError): BackupOutcome = when (error) {
        DriveError.NotSignedIn -> BackupOutcome.Skipped(BackupSkipReason.NOT_CONNECTED)
        DriveError.AccountChanged -> BackupOutcome.Skipped(BackupSkipReason.NOT_APPLICABLE)
        DriveError.NeedsUserAction -> BackupOutcome.NeedsUserAction
        is DriveError.AuthFailure -> authFailure(error.retryable)
        DriveError.NotFound -> BackupOutcome.Failed(BackupErrorKind.COPY_MISSING)
        is DriveError.Forbidden -> BackupOutcome.Failed(
            if (error.reason == DriveBackupClient.STORAGE_QUOTA_EXCEEDED) BackupErrorKind.STORAGE_FULL else BackupErrorKind.FORBIDDEN,
        )
        is DriveError.Transient -> BackupOutcome.Retry(BackupErrorKind.DRIVE_UNAVAILABLE)
        DriveError.Unavailable, DriveError.UnknownCreateResult -> BackupOutcome.Retry(BackupErrorKind.NETWORK)
        DriveError.SearchRequired, is DriveError.InvalidResponse -> BackupOutcome.Retry(BackupErrorKind.DRIVE_UNAVAILABLE)
        DriveError.TooLarge -> BackupOutcome.Failed(BackupErrorKind.TOO_LARGE)
        is DriveError.HttpError -> BackupOutcome.Failed(BackupErrorKind.UNEXPECTED)
    }

    private fun authFailure(retryable: Boolean): BackupOutcome =
        if (retryable) BackupOutcome.Retry(BackupErrorKind.AUTH_FAILED) else BackupOutcome.Failed(BackupErrorKind.AUTH_FAILED)

    private suspend fun exclusive(
        operation: BackupOperation,
        wait: Boolean = false,
        onLocked: () -> Unit = {},
        block: suspend () -> BackupOutcome,
    ): BackupOutcome {
        if (store.isRestoring.value) return BackupOutcome.Skipped(BackupSkipReason.RESTORING)
        // Locks are taken before any database access; a restore started outside the coordinator is re-checked after waiting.
        if (wait) mutex.lock() else if (!mutex.tryLock()) return BackupOutcome.Skipped(BackupSkipReason.BUSY)
        try {
            onLocked()
            if (store.isRestoring.value) return BackupOutcome.Skipped(BackupSkipReason.RESTORING)
            setBusy(operation)
            val outcome = guardedBlock(block)
            publish(outcome)
            return outcome
        } finally {
            _state.update { it.copy(busy = null) }
            mutex.unlock()
        }
    }

    private suspend fun guarded(block: suspend () -> BackupOutcome): BackupOutcome {
        if (store.isRestoring.value) return BackupOutcome.Skipped(BackupSkipReason.RESTORING)
        val outcome = guardedBlock(block)
        publish(outcome)
        return outcome
    }

    private suspend fun guardedBlock(block: suspend () -> BackupOutcome): BackupOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageBusy) {
            BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY)
        } catch (e: StaleGeneration) {
            BackupOutcome.Retry(BackupErrorKind.STORAGE_BUSY)
        } catch (e: InvalidSnapshotException) {
            BackupOutcome.Failed(BackupErrorKind.INVALID_COPY)
        } catch (e: Throwable) {
            BackupOutcome.Failed(BackupErrorKind.UNEXPECTED)
        }

    private fun setBusy(operation: BackupOperation) {
        _state.update { it.copy(busy = operation) }
    }

    private suspend fun publish(outcome: BackupOutcome?) {
        val error: BackupErrorKind? = when (outcome) {
            null -> _state.value.lastError
            is BackupOutcome.Retry -> outcome.error
            is BackupOutcome.Failed -> outcome.error
            BackupOutcome.NeedsUserAction -> BackupErrorKind.NEEDS_USER_ACTION
            is BackupOutcome.Skipped -> if (outcome.reason == BackupSkipReason.BUSY) _state.value.lastError else null
            BackupOutcome.Uploaded, BackupOutcome.Completed -> null
        }
        try {
            val account = auth.currentAccount()
            val (saved, generation) = states.loadWithGeneration()
            val binding = account?.let { BackupBinding(it.id, generation) }
            val bound = binding != null && saved.isBoundTo(binding)
            val action = BackupGuard.nextAction(saved, binding)
            val phase = when (action) {
                BackupAction.ConnectAccount -> BackupPhase.NOT_CONNECTED
                BackupAction.Search ->
                    if (bound && saved.searchState == BackupSearchState.FAILED) BackupPhase.SEARCH_FAILED else BackupPhase.SEARCH_REQUIRED
                is BackupAction.AwaitChoice -> BackupPhase.AWAITING_CHOICE
                BackupAction.CreateInitialCopy, is BackupAction.UpdateCopy -> BackupPhase.ACTIVE
            }
            val preview = prepared.load()?.takeIf { it.binding == binding }?.report
            _state.update {
                it.copy(
                    accountLabel = account?.displayLabel,
                    phase = phase,
                    choicePostponed = action is BackupAction.AwaitChoice && action.postponed,
                    lastSuccessfulBackupAt = if (bound) saved.lastSuccessfulBackupAt else null,
                    lastError = error,
                    invalidCopyReason = if (error == BackupErrorKind.INVALID_COPY) it.invalidCopyReason else null,
                    restorePreview = preview,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(lastError = error) }
        }
    }

    private companion object {
        // Upper bound on state-machine steps per operation (search, create, vanished copy, search again, create).
        const val MAX_STEPS = 6
    }
}
