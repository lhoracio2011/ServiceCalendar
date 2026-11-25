package app.servicecalendar

import android.app.Application
import app.servicecalendar.auth.AndroidGoogleAuth
import app.servicecalendar.backup.BackupScheduler
import app.servicecalendar.db.initDatabaseContext

class ServiceCalendarApplication : Application() {
    lateinit var googleAuth: AndroidGoogleAuth
        private set

    override fun onCreate() {
        super.onCreate()
        initDatabaseContext(this)
        val container = AppContainer.initialize()
        googleAuth = AndroidGoogleAuth(this)
        container.setGoogleAuthProvider(googleAuth)
        BackupScheduler.schedule(this)
    }
}
