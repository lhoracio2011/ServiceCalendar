package app.servicecalendar.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.servicecalendar.R

/**
 * The backup started when the user leaves the app. It goes through the same automatic path as the daily job, so uploads happen only
 * when the data changed and never while a restore decision is pending. Expedited work on Android 11 and older runs as a short foreground
 * service, which needs a notification; Android 12 and newer do not call [getForegroundInfo].
 */
class LeaveBackupWorker(context: Context, params: WorkerParameters) : BackupWorker(context, params) {

    override suspend fun run(coordinator: BackupCoordinator): BackupOutcome = coordinator.runOnLeave()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.backup_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            android.app.Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(applicationContext)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.backup_notification_title))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "service-calendar-backup"
        const val NOTIFICATION_ID = 4101
    }
}
