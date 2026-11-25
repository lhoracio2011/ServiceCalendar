package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import app.servicecalendar.db.query
import app.servicecalendar.domain.LimitExceeded

/** [table] must be a code constant, never user input. */
internal fun SQLiteConnection.requireRoom(table: String, max: Int, what: String, adding: Int = 1) {
    val count = query("SELECT COUNT(*) FROM $table", mapper = { it.getLong(0) }).single()
    if (count + adding > max) throw LimitExceeded(what, max)
}
