package app.servicecalendar.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.servicecalendar.AppContainer
import kotlin.coroutines.cancellation.CancellationException

open class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = try {
            run(AppContainer.instance.backupCoordinator)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background backup failed to start: ${e::class.simpleName}")
            return Result.failure()
        }
        val decision = decide(outcome, runAttemptCount)
        Log.i(TAG, "Background backup: $outcome → $decision (attempt ${runAttemptCount + 1})")
        return when (decision) {
            WorkDecision.SUCCESS -> Result.success()
            WorkDecision.RETRY -> Result.retry()
            WorkDecision.FAILURE -> Result.failure()
        }
    }

    protected open suspend fun run(coordinator: BackupCoordinator): BackupOutcome = coordinator.runScheduled()

    enum class WorkDecision { SUCCESS, RETRY, FAILURE }

    companion object {
        private const val TAG = "ServiceCalendarBackup"

        private val TRANSIENT_SKIPS = setOf(
            BackupSkipReason.BUSY,
            BackupSkipReason.RESTORING,
            BackupSkipReason.CONSENT_IN_PROGRESS,
        )

        // Transient failures retry with backoff; after this many attempts the run ends and the next daily window or app open tries again.
        const val MAX_RETRY_ATTEMPTS = 5

        fun decide(outcome: BackupOutcome, runAttemptCount: Int): WorkDecision = when (outcome) {
            is BackupOutcome.Retry ->
                if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) WorkDecision.RETRY else WorkDecision.FAILURE
            is BackupOutcome.Failed -> WorkDecision.FAILURE
            is BackupOutcome.Skipped ->
                if (outcome.reason in TRANSIENT_SKIPS && runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                    WorkDecision.RETRY
                } else {
                    WorkDecision.SUCCESS
                }
            BackupOutcome.NeedsUserAction,
            BackupOutcome.Uploaded,
            BackupOutcome.Completed,
            -> WorkDecision.SUCCESS
        }
    }
}
