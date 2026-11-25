package app.servicecalendar.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.servicecalendar.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

object BackupScheduler {
    private const val TAG = "ServiceCalendarBackup"

    const val UNIQUE_WORK_NAME = "service-calendar-daily-backup"

    const val LEAVE_WORK_NAME = "service-calendar-leave-backup"

    private const val APP_OPEN_MIN_INTERVAL_MS = 10 * 60 * 1000L

    private val lastAppOpenAttempt = AtomicLong(0L)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appOpenJob: Job? = null

    private var leaveJob: Job? = null

    @Synchronized
    fun launchOnAppOpen(context: Context) {
        if (appOpenJob?.isActive == true) return
        val appContext = context.applicationContext
        appOpenJob = appScope.launch { runOnAppOpen(appContext) }
    }

    /**
     * The user left the app: queues one expedited upload if a signed-in account has data that differs from its last backup.
     * One unique request at most; a newer one replaces a pending or running one, which re-reads the data. When the expedited quota is used up the
     * request runs as ordinary work. The worker applies all coordinator guards again (pending restore decision, busy, consent).
     */
    @Synchronized
    fun enqueueOnLeave(context: Context) {
        if (leaveJob?.isActive == true) return
        val appContext = context.applicationContext
        leaveJob = appScope.launch {
            val changed = try {
                AppContainer.instance.backupCoordinator.hasChangesToBackUp()
            } catch (e: IllegalStateException) {
                false
            }
            if (!changed) return@launch
            val request = OneTimeWorkRequestBuilder<LeaveBackupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            try {
                WorkManager.getInstance(appContext).enqueueUniqueWork(LEAVE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to queue backup on leave: ${e::class.simpleName}")
            }
        }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        try {
            WorkManager.getInstance(context)
                // UPDATE refreshes constraints and backoff of the existing work while keeping its schedule.
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to schedule daily backup: ${e::class.simpleName}")
        }
    }

    suspend fun runOnAppOpen(context: Context): BackupOutcome? {
        val coordinator = try {
            AppContainer.instance.backupCoordinator
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Backup on open failed to start: ${e::class.simpleName}")
            return null
        }
        // Skipped during consent: returning from the consent screen also reopens the app, and the 10-minute window must not be spent.
        if (coordinator.isConsentInProgress) return null
        val now = SystemClock.elapsedRealtime()
        val last = lastAppOpenAttempt.get()
        // At most one attempt per 10 minutes; also skipped while consent is in progress so the window is not wasted.
        if (last != 0L && now - last < APP_OPEN_MIN_INTERVAL_MS) return null
        if (!hasNetwork(context)) return null
        if (!lastAppOpenAttempt.compareAndSet(last, now)) return null
        return try {
            coordinator.runOnAppOpen().also {
                Log.i(TAG, "Backup on open: $it")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Backup on open failed to start: ${e::class.simpleName}")
            null
        }
    }

    private fun hasNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
