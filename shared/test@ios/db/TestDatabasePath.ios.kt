package app.servicecalendar.db

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun newTestDatabasePath(): String =
    NSTemporaryDirectory().trimEnd('/') + "/smoke-${NSUUID().UUIDString}.db"

@OptIn(ExperimentalForeignApi::class)
actual fun deleteTestDatabase(path: String) {
    listOf("", "-wal", "-shm", "-journal").forEach {
        NSFileManager.defaultManager.removeItemAtPath(path + it, error = null)
    }
}

actual fun isNativeSqliteAvailable(): Boolean = true
