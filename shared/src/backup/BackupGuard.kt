package app.servicecalendar.backup

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

sealed interface BackupAction {
    data object ConnectAccount : BackupAction

    data object Search : BackupAction

    data class AwaitChoice(val driveFileId: String, val postponed: Boolean) : BackupAction {
        override fun toString(): String = "AwaitChoice(postponed=$postponed)"
    }

    data object CreateInitialCopy : BackupAction

    data class UpdateCopy(val driveFileId: String) : BackupAction {
        override fun toString(): String = "UpdateCopy"
    }
}

class ReplaceConfirmation internal constructor(
    val binding: BackupBinding,
    val driveFileId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ReplaceConfirmation && other.binding == binding && other.driveFileId == driveFileId

    override fun hashCode(): Int = 31 * binding.hashCode() + driveFileId.hashCode()

    override fun toString(): String = "ReplaceConfirmation($binding)"
}

/**
 * Pure state machine guarding an existing Drive copy; callers persist the returned state, ideally via BackupStateStore.update.
 * State bound to a different account or data generation permits nothing. Events that do not match the state are ignored.
 */
object BackupGuard {
    fun canUpload(state: BackupState, binding: BackupBinding?): Boolean =
        binding != null && state.isBoundTo(binding) && state.dailyBackupsAllowed

    fun nextAction(state: BackupState, binding: BackupBinding?): BackupAction {
        if (binding == null) return BackupAction.ConnectAccount
        if (!state.isBoundTo(binding)) return BackupAction.Search
        return when (state.searchState) {
            BackupSearchState.NOT_SEARCHED,
            BackupSearchState.SEARCHING,
            BackupSearchState.FAILED,
            -> BackupAction.Search

            BackupSearchState.FOUND_NONE ->
                state.driveFileId?.let(BackupAction::UpdateCopy) ?: BackupAction.CreateInitialCopy

            BackupSearchState.FOUND_COPY -> {
                val fileId = checkNotNull(state.driveFileId)
                when (state.choice) {
                    BackupChoice.NONE -> BackupAction.AwaitChoice(fileId, postponed = false)
                    BackupChoice.POSTPONED -> BackupAction.AwaitChoice(fileId, postponed = true)
                    BackupChoice.RESTORE, BackupChoice.REPLACE_CONFIRMED -> BackupAction.UpdateCopy(fileId)
                }
            }
        }
    }

    fun bind(state: BackupState, binding: BackupBinding): BackupState =
        if (state.isBoundTo(binding)) state else BackupState.freshFor(binding)

    @Suppress("UNUSED_PARAMETER")
    fun onSearchStarted(state: BackupState, binding: BackupBinding): BackupState =
        BackupState.freshFor(binding).copy(searchState = BackupSearchState.SEARCHING)

    fun onSearchFoundNone(state: BackupState, binding: BackupBinding): BackupState =
        if (isSearching(state, binding)) state.copy(searchState = BackupSearchState.FOUND_NONE) else state

    fun onSearchFoundCopy(
        state: BackupState,
        binding: BackupBinding,
        driveFileId: String,
        remoteRevision: String? = null,
    ): BackupState =
        if (isSearching(state, binding)) {
            state.copy(searchState = BackupSearchState.FOUND_COPY, driveFileId = driveFileId, remoteRevision = remoteRevision)
        } else {
            state
        }

    fun onSearchFailed(state: BackupState, binding: BackupBinding): BackupState =
        if (isSearching(state, binding)) state.copy(searchState = BackupSearchState.FAILED) else state

    fun postpone(state: BackupState, binding: BackupBinding): BackupState =
        if (isAwaitingChoice(state, binding)) state.copy(choice = BackupChoice.POSTPONED) else state

    @Suppress("UNUSED_PARAMETER")
    fun dismissChoice(state: BackupState, binding: BackupBinding): BackupState = state

    fun requestReplace(state: BackupState, binding: BackupBinding): ReplaceConfirmation? =
        if (isAwaitingChoice(state, binding)) ReplaceConfirmation(binding, checkNotNull(state.driveFileId)) else null

    fun confirmReplace(state: BackupState, binding: BackupBinding, confirmation: ReplaceConfirmation): BackupState =
        if (
            isAwaitingChoice(state, binding) &&
            confirmation.binding == binding &&
            confirmation.driveFileId == state.driveFileId
        ) {
            state.copy(choice = BackupChoice.REPLACE_CONFIRMED)
        } else {
            state
        }

    /** Should be written in the same transaction as the data replacement. If the process dies in between, the new generation no longer matches the binding and a fresh search is required (safe side). */
    fun onRestoreSucceeded(
        restored: BackupBinding,
        driveFileId: String,
        copyCreatedAt: Instant? = null,
        remoteRevision: String? = null,
    ): BackupState = BackupState(
        accountId = restored.accountId,
        searchState = BackupSearchState.FOUND_COPY,
        choice = BackupChoice.RESTORE,
        driveFileId = driveFileId,
        lastSuccessfulBackupAt = copyCreatedAt,
        boundGeneration = restored.dataGeneration,
        remoteRevision = remoteRevision,
    )

    fun onUploadSucceeded(
        state: BackupState,
        binding: BackupBinding,
        driveFileId: String,
        at: Instant,
        remoteRevision: String? = null,
    ): BackupState =
        if (canUpload(state, binding)) {
            state.copy(driveFileId = driveFileId, lastSuccessfulBackupAt = at, remoteRevision = remoteRevision)
        } else {
            state
        }

    /**
     * True if the remote copy is still what this device last wrote or restored. With a stored revision marker the markers must match;
     * without one (pre-v3 state) the copy counts as ours only if modified no later than the last success plus a clock tolerance. Unknown means false.
     */
    fun isRemoteCopyUnchanged(state: BackupState, remote: DriveFile): Boolean {
        if (remote.id != state.driveFileId) return false
        state.remoteRevision?.let { return it == remote.revisionMarker }
        val modified = remote.modifiedTime ?: return false
        val lastSuccess = state.lastSuccessfulBackupAt ?: return false
        return modified <= lastSuccess + LEGACY_CLOCK_TOLERANCE
    }

    fun onCopyChangedRemotely(state: BackupState, binding: BackupBinding, remote: DriveFile): BackupState =
        if (canUpload(state, binding) && state.driveFileId == remote.id) {
            state.copy(
                searchState = BackupSearchState.FOUND_COPY,
                choice = BackupChoice.NONE,
                remoteRevision = remote.revisionMarker,
            )
        } else {
            state
        }

    fun onCopyMissing(state: BackupState, binding: BackupBinding): BackupState =
        if (state.isBoundTo(binding)) BackupState.freshFor(binding) else state

    /** Allowed device/Drive clock skew when comparing timestamps; a foreign write within this window goes unnoticed (accepted residual risk). */
    val LEGACY_CLOCK_TOLERANCE: Duration = 5.minutes

    private fun isSearching(state: BackupState, binding: BackupBinding): Boolean =
        state.isBoundTo(binding) && state.searchState == BackupSearchState.SEARCHING

    private fun isAwaitingChoice(state: BackupState, binding: BackupBinding): Boolean =
        state.isBoundTo(binding) &&
            state.searchState == BackupSearchState.FOUND_COPY &&
            (state.choice == BackupChoice.NONE || state.choice == BackupChoice.POSTPONED)
}
