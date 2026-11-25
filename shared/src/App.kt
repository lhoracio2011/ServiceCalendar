package app.servicecalendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.servicecalendar.ui.AppShell
import app.servicecalendar.ui.theme.ServiceCalendarTheme
import org.jetbrains.compose.reload.DevelopmentEntryPoint

@Composable
@Preview
@DevelopmentEntryPoint
fun App() {
    ServiceCalendarTheme {
        Box(Modifier.fillMaxSize()) {
            AppShell()
            Box(
                Modifier
                    .fillMaxWidth()
                    // Status bar icon colour is set by the platform (MainActivity on Android, iosApp.swift on iOS).
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
