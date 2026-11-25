package app.servicecalendar.backup

import app.servicecalendar.backup.BackupChoice.NONE
import app.servicecalendar.backup.BackupChoice.POSTPONED
import app.servicecalendar.backup.BackupChoice.REPLACE_CONFIRMED
import app.servicecalendar.backup.BackupChoice.RESTORE
import app.servicecalendar.backup.BackupSearchState.FAILED
import app.servicecalendar.backup.BackupSearchState.FOUND_COPY
import app.servicecalendar.backup.BackupSearchState.FOUND_NONE
import app.servicecalendar.backup.BackupSearchState.NOT_SEARCHED
import app.servicecalendar.backup.BackupSearchState.SEARCHING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The backup guard that protects an existing Drive copy.
 *
 * The state machine is pure: state is passed explicitly, so no database is needed.
 * Persisting the state is covered by `BackupStateStoreTest`.
 */
class BackupGuardTest {
    private val accountA = BackupBinding(accountId = "account-a", dataGeneration = 1)
    private val accountB = BackupBinding(accountId = "account-b", dataGeneration = 1)
    private val noon = Instant.parse("2026-11-10T12:00:00Z")

    private fun searching(binding: BackupBinding = accountA): BackupState =
        BackupGuard.onSearchStarted(BackupState.INITIAL, binding)

    private fun foundCopy(binding: BackupBinding = accountA, fileId: String = "file-1"): BackupState =
        BackupGuard.onSearchFoundCopy(searching(binding), binding, fileId)

    private fun foundNone(binding: BackupBinding = accountA): BackupState =
        BackupGuard.onSearchFoundNone(searching(binding), binding)

    private fun replaceConfirmed(binding: BackupBinding = accountA): BackupState {
        val found = foundCopy(binding)
        val confirmation = assertNotNull(BackupGuard.requestReplace(found, binding))
        return BackupGuard.confirmReplace(found, binding, confirmation)
    }

    private fun assertGuardOn(state: BackupState, binding: BackupBinding?) {
        assertFalse(BackupGuard.canUpload(state, binding), "uploads must be forbidden: $state")
    }

    @Test
    fun `initial state forbids uploads and asks to connect an account`() {
        val state = BackupState.INITIAL
        assertNull(state.accountId)
        assertEquals(NOT_SEARCHED, state.searchState)
        assertEquals(NONE, state.choice)
        assertFalse(state.dailyBackupsAllowed)
        assertGuardOn(state, null)
        assertGuardOn(state, accountA)
        assertEquals(BackupAction.ConnectAccount, BackupGuard.nextAction(state, null))
    }

    @Test
    fun `connected account without a search forbids uploads and requires a search`() {
        val bound = BackupGuard.bind(BackupState.INITIAL, accountA)
        assertEquals(accountA.accountId, bound.accountId)
        assertEquals(accountA.dataGeneration, bound.boundGeneration)
        assertEquals(NOT_SEARCHED, bound.searchState)
        assertGuardOn(bound, accountA)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(bound, accountA))
    }

    @Test
    fun `uploads stay forbidden while the search is running`() {
        val state = searching()
        assertEquals(SEARCHING, state.searchState)
        assertGuardOn(state, accountA)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(state, accountA))
    }

    @Test
    fun `search failure keeps the guard on and requires a new search`() {
        val failed = BackupGuard.onSearchFailed(searching(), accountA)
        assertEquals(FAILED, failed.searchState)
        assertGuardOn(failed, accountA)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(failed, accountA))

        // Retrying after a failure searches again instead of uploading.
        val retry = BackupGuard.onSearchStarted(failed, accountA)
        assertGuardOn(retry, accountA)
        assertEquals(FOUND_NONE, BackupGuard.onSearchFoundNone(retry, accountA).searchState)
    }

    @Test
    fun `no copy found allows the initial copy`() {
        val state = foundNone()
        assertEquals(FOUND_NONE, state.searchState)
        assertTrue(state.dailyBackupsAllowed)
        assertTrue(BackupGuard.canUpload(state, accountA))
        assertEquals(BackupAction.CreateInitialCopy, BackupGuard.nextAction(state, accountA))
    }

    @Test
    fun `successful initial copy records file id and time and switches to updates`() {
        val uploaded = BackupGuard.onUploadSucceeded(foundNone(), accountA, "own-file", noon)
        assertEquals("own-file", uploaded.driveFileId)
        assertEquals(noon, uploaded.lastSuccessfulBackupAt)
        assertTrue(BackupGuard.canUpload(uploaded, accountA))
        assertEquals(BackupAction.UpdateCopy("own-file"), BackupGuard.nextAction(uploaded, accountA))
    }

    @Test
    fun `found copy requires a choice and forbids uploads`() {
        val state = foundCopy(fileId = "remote")
        assertEquals(FOUND_COPY, state.searchState)
        assertEquals("remote", state.driveFileId)
        assertFalse(state.dailyBackupsAllowed)
        assertGuardOn(state, accountA)
        assertEquals(BackupAction.AwaitChoice("remote", postponed = false), BackupGuard.nextAction(state, accountA))
    }

    @Test
    fun `decide later keeps the guard on`() {
        val postponed = BackupGuard.postpone(foundCopy(fileId = "remote"), accountA)
        assertEquals(POSTPONED, postponed.choice)
        assertGuardOn(postponed, accountA)
        assertEquals(BackupAction.AwaitChoice("remote", postponed = true), BackupGuard.nextAction(postponed, accountA))
    }

    @Test
    fun `dismissing the choice dialog keeps the guard on`() {
        val found = foundCopy()
        val dismissed = BackupGuard.dismissChoice(found, accountA)
        assertEquals(found, dismissed)
        assertGuardOn(dismissed, accountA)
        assertEquals(BackupAction.AwaitChoice("file-1", postponed = false), BackupGuard.nextAction(dismissed, accountA))
    }

    @Test
    fun `restart keeps the unfinished choice because the guard holds no memory of its own`() {
        val postponed = BackupGuard.postpone(foundCopy(), accountA)
        // Restart: the state is reloaded from storage as a new object with the same fields.
        val reloaded = postponed.copy()
        assertGuardOn(reloaded, accountA)
        assertEquals(BackupAction.AwaitChoice("file-1", postponed = true), BackupGuard.nextAction(reloaded, accountA))

        // A replace request that was never confirmed saved nothing before the restart.
        val requested = foundCopy()
        assertNotNull(BackupGuard.requestReplace(requested, accountA))
        val afterRestart = requested.copy()
        assertEquals(NONE, afterRestart.choice)
        assertGuardOn(afterRestart, accountA)
    }

    @Test
    fun `replace needs a separate confirmation step`() {
        val found = foundCopy(fileId = "remote")
        val confirmation = assertNotNull(BackupGuard.requestReplace(found, accountA))
        assertEquals("remote", confirmation.driveFileId)
        assertEquals(accountA, confirmation.binding)
        // Choosing "Replace" alone allows nothing yet: the state does not change.
        assertGuardOn(found, accountA)
        assertEquals(NONE, found.choice)
    }

    @Test
    fun `confirmed replace allows daily copies over the found copy`() {
        val state = replaceConfirmed()
        assertEquals(REPLACE_CONFIRMED, state.choice)
        assertTrue(state.dailyBackupsAllowed)
        assertTrue(BackupGuard.canUpload(state, accountA))
        assertEquals(BackupAction.UpdateCopy("file-1"), BackupGuard.nextAction(state, accountA))
    }

    @Test
    fun `replace confirmed from the postponed state is allowed`() {
        val postponed = BackupGuard.postpone(foundCopy(), accountA)
        val confirmation = assertNotNull(BackupGuard.requestReplace(postponed, accountA))
        val confirmed = BackupGuard.confirmReplace(postponed, accountA, confirmation)
        assertTrue(BackupGuard.canUpload(confirmed, accountA))
    }

    @Test
    fun `replace is not offered without a found copy`() {
        assertNull(BackupGuard.requestReplace(BackupState.INITIAL, accountA))
        assertNull(BackupGuard.requestReplace(searching(), accountA))
        assertNull(BackupGuard.requestReplace(foundNone(), accountA))
        assertNull(BackupGuard.requestReplace(BackupGuard.onSearchFailed(searching(), accountA), accountA))
        assertNull(BackupGuard.requestReplace(foundCopy(accountB), accountA))
    }

    @Test
    fun `replace confirmation for another account or copy is rejected`() {
        val foundForA = foundCopy(fileId = "copy-a")
        val confirmationA = assertNotNull(BackupGuard.requestReplace(foundForA, accountA))

        // The account changed between the request and the confirmation.
        val switched = BackupGuard.bind(foundForA, accountB)
        val foundForB = BackupGuard.onSearchFoundCopy(BackupGuard.onSearchStarted(switched, accountB), accountB, "copy-a")
        assertGuardOn(BackupGuard.confirmReplace(foundForB, accountB, confirmationA), accountB)

        // A new search found a different copy, so the old confirmation is void.
        val refound = BackupGuard.onSearchFoundCopy(BackupGuard.onSearchStarted(foundForA, accountA), accountA, "copy-new")
        assertGuardOn(BackupGuard.confirmReplace(refound, accountA, confirmationA), accountA)
    }

    @Test
    fun `successful restore allows daily copies for the restored generation`() {
        val restoredBinding = BackupBinding(accountA.accountId, dataGeneration = 2)
        val restored = BackupGuard.onRestoreSucceeded(restoredBinding, "file-1", copyCreatedAt = noon)
        assertEquals(RESTORE, restored.choice)
        assertEquals(FOUND_COPY, restored.searchState)
        assertEquals(2L, restored.boundGeneration)
        assertEquals(noon, restored.lastSuccessfulBackupAt)
        assertTrue(BackupGuard.canUpload(restored, restoredBinding))
        assertEquals(BackupAction.UpdateCopy("file-1"), BackupGuard.nextAction(restored, restoredBinding))
        // The old (pre-restore) generation gets no permission.
        assertGuardOn(restored, accountA)
    }

    @Test
    fun `account change resets the permission and requires a new search`() {
        val allowedForA = replaceConfirmed(accountA)
        assertTrue(BackupGuard.canUpload(allowedForA, accountA))

        assertGuardOn(allowedForA, accountB)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(allowedForA, accountB))

        val rebound = BackupGuard.bind(allowedForA, accountB)
        assertEquals(BackupState.freshFor(accountB), rebound)
        assertEquals(accountB.accountId, rebound.accountId)
        assertEquals(NOT_SEARCHED, rebound.searchState)
        assertNull(rebound.driveFileId)
        assertNull(rebound.lastSuccessfulBackupAt)
        assertGuardOn(rebound, accountB)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(rebound, accountB))
    }

    @Test
    fun `switching back to the previous account still requires a new search`() {
        val allowedForA = foundNone(accountA)
        val backToA = BackupGuard.bind(BackupGuard.bind(allowedForA, accountB), accountA)
        assertGuardOn(backToA, accountA)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(backToA, accountA))
    }

    @Test
    fun `signing in again with the same account keeps the unfinished choice`() {
        val postponed = BackupGuard.postpone(foundCopy(), accountA)
        val again = BackupGuard.bind(postponed, accountA)
        assertSame(postponed, again)
        assertGuardOn(again, accountA)
        assertEquals(POSTPONED, again.choice)
    }

    @Test
    fun `another local data generation resets the guard`() {
        val allowed = foundNone(accountA)
        val otherDataset = BackupBinding(accountA.accountId, dataGeneration = 2)
        assertGuardOn(allowed, otherDataset)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(allowed, otherDataset))
        val rebound = BackupGuard.bind(allowed, otherDataset)
        assertEquals(BackupState.freshFor(otherDataset), rebound)
        assertGuardOn(rebound, otherDataset)
    }

    @Test
    fun `stale search result after an account change is ignored`() {
        val searchingA = searching(accountA)
        val nowB = BackupGuard.onSearchStarted(searchingA, accountB)
        assertEquals(nowB, BackupGuard.onSearchFoundNone(nowB, accountA))
        assertEquals(nowB, BackupGuard.onSearchFoundCopy(nowB, accountA, "copy-a"))
        assertEquals(nowB, BackupGuard.onSearchFailed(nowB, accountA))
        assertGuardOn(nowB, accountB)
    }

    @Test
    fun `search result without a started search is ignored`() {
        val bound = BackupGuard.bind(BackupState.INITIAL, accountA)
        assertEquals(bound, BackupGuard.onSearchFoundNone(bound, accountA))
        assertGuardOn(BackupGuard.onSearchFoundNone(bound, accountA), accountA)

        val postponed = BackupGuard.postpone(foundCopy(), accountA)
        assertEquals(postponed, BackupGuard.onSearchFoundNone(postponed, accountA))
    }

    @Test
    fun `postpone is ignored outside a pending choice`() {
        val allowed = foundNone()
        assertEquals(allowed, BackupGuard.postpone(allowed, accountA))
        val confirmed = replaceConfirmed()
        assertEquals(confirmed, BackupGuard.postpone(confirmed, accountA))
        assertEquals(foundCopy(), BackupGuard.postpone(foundCopy(), accountB))
    }

    @Test
    fun `upload result is recorded only while uploads are allowed for that binding`() {
        val found = foundCopy()
        assertEquals(found, BackupGuard.onUploadSucceeded(found, accountA, "file-1", noon))

        val allowedForB = foundNone(accountB)
        assertEquals(allowedForB, BackupGuard.onUploadSucceeded(allowedForB, accountA, "file-a", noon))

        val allowed = replaceConfirmed()
        val uploaded = BackupGuard.onUploadSucceeded(allowed, accountA, "file-1", noon)
        assertEquals(noon, uploaded.lastSuccessfulBackupAt)
        assertEquals(REPLACE_CONFIRMED, uploaded.choice)
    }

    @Test
    fun `new search resets an earlier permission until it finishes`() {
        val allowed = BackupGuard.onUploadSucceeded(foundNone(), accountA, "own-file", noon)
        val again = BackupGuard.onSearchStarted(allowed, accountA)
        assertEquals(SEARCHING, again.searchState)
        assertNull(again.driveFileId)
        assertNull(again.lastSuccessfulBackupAt)
        assertGuardOn(again, accountA)
    }

    @Test
    fun `missing copy on Drive requires a new search`() {
        val allowed = BackupGuard.onUploadSucceeded(foundNone(), accountA, "own-file", noon)
        val missing = BackupGuard.onCopyMissing(allowed, accountA)
        assertEquals(BackupState.freshFor(accountA), missing)
        assertGuardOn(missing, accountA)
        assertEquals(BackupAction.Search, BackupGuard.nextAction(missing, accountA))
        // An event for another binding does not touch the state.
        assertEquals(allowed, BackupGuard.onCopyMissing(allowed, accountB))
    }

    /** Simulates a background run: the state is reloaded from "storage" and there is no UI. */
    private fun backgroundRun(stored: BackupState, currentAccount: BackupBinding?): BackupAction {
        val reloaded = stored.copy()
        val action = BackupGuard.nextAction(reloaded, currentAccount)
        val uploadAllowed = BackupGuard.canUpload(reloaded, currentAccount)
        assertEquals(
            action is BackupAction.CreateInitialCopy || action is BackupAction.UpdateCopy,
            uploadAllowed,
            "nextAction and canUpload must agree: $action",
        )
        return action
    }

    @Test
    fun `background run with a pending choice does not upload`() {
        val pending = foundCopy()
        assertEquals(BackupAction.AwaitChoice("file-1", postponed = false), backgroundRun(pending, accountA))
        val postponed = BackupGuard.postpone(pending, accountA)
        assertEquals(BackupAction.AwaitChoice("file-1", postponed = true), backgroundRun(postponed, accountA))
    }

    @Test
    fun `background run without an account or with another account does not upload`() {
        val allowed = replaceConfirmed()
        assertEquals(BackupAction.ConnectAccount, backgroundRun(allowed, null))
        assertEquals(BackupAction.Search, backgroundRun(allowed, accountB))
    }

    @Test
    fun `background run uploads only after the conflict is resolved`() {
        assertEquals(BackupAction.UpdateCopy("file-1"), backgroundRun(replaceConfirmed(), accountA))
        assertEquals(BackupAction.CreateInitialCopy, backgroundRun(foundNone(), accountA))
    }

    @Test
    fun `inconsistent states cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { BackupState(accountId = "a", boundGeneration = null) }
        assertFailsWith<IllegalArgumentException> { BackupState(accountId = null, boundGeneration = 1) }
        assertFailsWith<IllegalArgumentException> { BackupState(searchState = SEARCHING) }
        assertFailsWith<IllegalArgumentException> {
            BackupState(accountId = "a", boundGeneration = 1, searchState = NOT_SEARCHED, choice = RESTORE)
        }
        assertFailsWith<IllegalArgumentException> {
            BackupState(accountId = "a", boundGeneration = 1, searchState = FOUND_COPY, driveFileId = null)
        }
        assertFailsWith<IllegalArgumentException> {
            BackupState(accountId = "a", boundGeneration = 1, searchState = SEARCHING, driveFileId = "f")
        }
        assertFailsWith<IllegalArgumentException> { BackupBinding(accountId = " ", dataGeneration = 1) }
        assertFailsWith<IllegalArgumentException> { BackupBinding(accountId = "a", dataGeneration = 0) }
    }

    private fun ownCopy(revision: String? = "rev-1"): BackupState =
        BackupGuard.onUploadSucceeded(foundNone(), accountA, "own-file", noon, revision)

    @Test
    fun `upload search and restore remember the revision of the copy`() {
        assertEquals("rev-1", ownCopy().remoteRevision)
        assertEquals("rev-9", BackupGuard.onSearchFoundCopy(searching(), accountA, "file-1", "rev-9").remoteRevision)
        assertEquals("rev-5", BackupGuard.onRestoreSucceeded(accountA, "file-1", noon, "rev-5").remoteRevision)
        // A new search and a missing copy reset the marker together with the file ID.
        assertNull(BackupGuard.onSearchStarted(ownCopy(), accountA).remoteRevision)
        assertNull(BackupGuard.onCopyMissing(ownCopy(), accountA).remoteRevision)
        assertFailsWith<IllegalArgumentException> { BackupState.freshFor(accountA).copy(remoteRevision = "rev-1") }
    }

    @Test
    fun `copy is unchanged only while its revision matches the stored one`() {
        val state = ownCopy()
        assertTrue(BackupGuard.isRemoteCopyUnchanged(state, DriveFile("own-file", noon, "rev-1")))
        assertFalse(BackupGuard.isRemoteCopyUnchanged(state, DriveFile("own-file", noon, "rev-2")))
        assertFalse(BackupGuard.isRemoteCopyUnchanged(state, DriveFile("own-file", noon)))
        assertFalse(BackupGuard.isRemoteCopyUnchanged(state, DriveFile("other-file", noon, "rev-1")))
    }

    @Test
    fun `state without a revision adopts only a copy not modified after its last backup`() {
        val legacy = ownCopy(revision = null)
        val tolerance = BackupGuard.LEGACY_CLOCK_TOLERANCE
        assertTrue(BackupGuard.isRemoteCopyUnchanged(legacy, DriveFile("own-file", noon - tolerance, "rev-1")))
        assertTrue(BackupGuard.isRemoteCopyUnchanged(legacy, DriveFile("own-file", noon + tolerance, "rev-1")))
        assertFalse(BackupGuard.isRemoteCopyUnchanged(legacy, DriveFile("own-file", noon + tolerance + 1.seconds, "rev-1")))
        assertFalse(BackupGuard.isRemoteCopyUnchanged(legacy, DriveFile("own-file", null, "rev-1")))
        // Without the time of the last backup the answer is unknown, so the user decides.
        val noTime = BackupGuard.onRestoreSucceeded(accountA, "own-file", copyCreatedAt = null)
        assertFalse(BackupGuard.isRemoteCopyUnchanged(noTime, DriveFile("own-file", noon, "rev-1")))
    }

    @Test
    fun `copy changed remotely returns to the choice with the new revision`() {
        val changed = BackupGuard.onCopyChangedRemotely(ownCopy(), accountA, DriveFile("own-file", noon, "rev-2"))
        assertEquals(FOUND_COPY, changed.searchState)
        assertEquals(NONE, changed.choice)
        assertEquals("own-file", changed.driveFileId)
        assertEquals("rev-2", changed.remoteRevision)
        assertEquals(noon, changed.lastSuccessfulBackupAt)
        assertGuardOn(changed, accountA)
        assertEquals(BackupAction.AwaitChoice("own-file", postponed = false), BackupGuard.nextAction(changed, accountA))

        // A confirmed replace also returns to the choice; another file, binding or a state without permission is unchanged.
        val confirmed = replaceConfirmed()
        assertEquals(NONE, BackupGuard.onCopyChangedRemotely(confirmed, accountA, DriveFile("file-1", noon, "rev-3")).choice)
        assertEquals(ownCopy(), BackupGuard.onCopyChangedRemotely(ownCopy(), accountA, DriveFile("other", noon, "rev-2")))
        assertEquals(ownCopy(), BackupGuard.onCopyChangedRemotely(ownCopy(), accountB, DriveFile("own-file", noon, "rev-2")))
        assertEquals(foundCopy(), BackupGuard.onCopyChangedRemotely(foundCopy(), accountA, DriveFile("file-1", noon, "rev-2")))
    }
}
