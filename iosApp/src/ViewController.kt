package app.servicecalendar

import androidx.compose.ui.window.ComposeUIViewController
import app.servicecalendar.backup.BridgedGoogleAuthProvider
import app.servicecalendar.backup.GoogleAuthBridge

fun ViewController() = ComposeUIViewController { App() }

/** Called once at process start (UI and background launches) before any backup code runs; [googleAuth] is the Swift OAuth bridge (GoogleDriveAuth.swift). */
fun setUpAppContainer(googleAuth: GoogleAuthBridge) {
    AppContainer.initialize().setGoogleAuthProvider(BridgedGoogleAuthProvider(googleAuth))
}
