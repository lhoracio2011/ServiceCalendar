package app.servicecalendar

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun interface BackupRunListener {
    fun onFinished(success: Boolean)
}

class BackupRun internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun backupAvailable(): Boolean = AppContainer.instance.hasGoogleAuthProvider

fun startScheduledBackup(listener: BackupRunListener): BackupRun = start(listener) { it.runScheduled() }

private val APP_OPEN_MIN_INTERVAL = 10.minutes

private val processStart = TimeSource.Monotonic.markNow()

@OptIn(ExperimentalAtomicApi::class)
private val lastAppOpenAttempt = AtomicLong(0L)

/** The coordinator for an automatic run, or null: no auth provider, setup failed, or consent in progress. */
private fun automaticCoordinator(): app.servicecalendar.backup.BackupCoordinator? {
    val coordinator = try {
        val container = AppContainer.instance
        if (!container.hasGoogleAuthProvider) return null
        container.backupCoordinator
    // An exception crossing into Swift would terminate the process, so any setup failure is reported as "not started".
    } catch (e: Throwable) {
        return null
    }
    return if (coordinator.isConsentInProgress) null else coordinator
}

/** The app went to the background. Returns null if nothing was started. The coordinator uploads only changed data; the caller checks network and keeps the app alive while the run lasts. */
fun startBackupOnLeave(listener: BackupRunListener): BackupRun? {
    automaticCoordinator() ?: return null
    return start(listener) { it.runOnLeave() }
}

/** Returns null if no attempt was started: no auth provider, consent in progress, or less than 10 minutes since the last attempt. The caller checks network. */
@OptIn(ExperimentalAtomicApi::class)
fun startBackupOnAppOpen(listener: BackupRunListener): BackupRun? {
    automaticCoordinator() ?: return null
    val now = processStart.elapsedNow().inWholeMilliseconds + 1
    val last = lastAppOpenAttempt.load()
    if (last != 0L && now - last < APP_OPEN_MIN_INTERVAL.inWholeMilliseconds) return null
    if (!lastAppOpenAttempt.compareAndSet(last, now)) return null
    return start(listener) { it.runOnAppOpen() }
}

private fun start(
    listener: BackupRunListener,
    action: suspend (app.servicecalendar.backup.BackupCoordinator) -> app.servicecalendar.backup.BackupOutcome,
): BackupRun {
    val job = scope.launch {
        val success = try {
            val container = AppContainer.instance
            if (!container.hasGoogleAuthProvider) {
                true
            } else {
                !action(container.backupCoordinator).shouldRetry
            }
        } catch (e: CancellationException) {
            false
        } catch (e: Throwable) {
            false
        }
        listener.onFinished(success)
    }
    return BackupRun(job)
}
