package app.servicecalendar.backup

import app.servicecalendar.backup.BackupWorker.WorkDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupWorkerDecisionTest {

    @Test
    fun temporaryErrorIsRetriedUntilLimit() {
        val outcome = BackupOutcome.Retry(BackupErrorKind.NETWORK)
        assertEquals(WorkDecision.RETRY, BackupWorker.decide(outcome, runAttemptCount = 0))
        assertEquals(WorkDecision.RETRY, BackupWorker.decide(outcome, BackupWorker.MAX_RETRY_ATTEMPTS - 2))
        assertEquals(WorkDecision.FAILURE, BackupWorker.decide(outcome, BackupWorker.MAX_RETRY_ATTEMPTS - 1))
    }

    @Test
    fun permanentErrorFails() {
        assertEquals(WorkDecision.FAILURE, BackupWorker.decide(BackupOutcome.Failed(BackupErrorKind.TOO_LARGE), 0))
    }

    @Test
    fun lostPermissionEndsQuietly() {
        assertEquals(WorkDecision.SUCCESS, BackupWorker.decide(BackupOutcome.NeedsUserAction, 0))
    }

    @Test
    fun skippedAndDoneSucceed() {
        val transient = setOf(BackupSkipReason.BUSY, BackupSkipReason.RESTORING, BackupSkipReason.CONSENT_IN_PROGRESS)
        BackupSkipReason.entries.filter { it !in transient }.forEach {
            assertEquals(WorkDecision.SUCCESS, BackupWorker.decide(BackupOutcome.Skipped(it), 0))
        }
        assertEquals(WorkDecision.SUCCESS, BackupWorker.decide(BackupOutcome.Uploaded, 0))
        assertEquals(WorkDecision.SUCCESS, BackupWorker.decide(BackupOutcome.Completed, 0))
    }

    @Test
    fun transientSkipsAreRetriedUntilLimit() {
        listOf(BackupSkipReason.BUSY, BackupSkipReason.RESTORING, BackupSkipReason.CONSENT_IN_PROGRESS).forEach {
            val outcome = BackupOutcome.Skipped(it)
            assertEquals(WorkDecision.RETRY, BackupWorker.decide(outcome, 0))
            assertEquals(WorkDecision.SUCCESS, BackupWorker.decide(outcome, BackupWorker.MAX_RETRY_ATTEMPTS - 1))
        }
    }
}
