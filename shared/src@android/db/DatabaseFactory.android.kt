package app.servicecalendar.db

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

private var appContext: Context? = null

fun initDatabaseContext(context: Context) {
    appContext = context.applicationContext
}

actual fun openDatabase(name: String): SQLiteConnection {
    val requested = File(name)
    val file = if (requested.isAbsolute) {
        requested
    } else {
        val context = checkNotNull(appContext) {
            "initDatabaseContext(context) must be called before openDatabase"
        }
        context.getDatabasePath(name)
    }
    file.parentFile?.mkdirs()
    return BundledSQLiteDriver().open(file.path)
}
