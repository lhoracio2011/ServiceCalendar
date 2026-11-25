package app.servicecalendar.db

import androidx.sqlite.SQLiteConnection

expect fun openDatabase(name: String): SQLiteConnection
