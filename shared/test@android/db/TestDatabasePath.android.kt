package app.servicecalendar.db

import java.io.File
import java.util.UUID

actual fun newTestDatabasePath(): String =
    File(System.getProperty("java.io.tmpdir"), "smoke-${UUID.randomUUID()}.db").path

actual fun deleteTestDatabase(path: String) {
    listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
}

actual fun isNativeSqliteAvailable(): Boolean =
    try {
        System.loadLibrary("sqliteJni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
