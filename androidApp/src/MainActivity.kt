package app.servicecalendar

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.servicecalendar.backup.BackupScheduler

class MainActivity : ComponentActivity() {

    private val googleAuth get() = (application as ServiceCalendarApplication).googleAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = if (night) {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                SystemBarStyle.dark(Color.TRANSPARENT)
            },
        )
        super.onCreate(savedInstanceState)
        googleAuth.attach(this)
        setContent { App() }
    }

    // Catches up on a backup window missed by the system. Returning from the consent screen also calls onStart; the scheduler skips that case.
    override fun onStart() {
        super.onStart()
        BackupScheduler.launchOnAppOpen(applicationContext)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
