package app.servicecalendar.db

import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals

/** Data written to a real database file survives closing and reopening it. */
class DatabaseSmokeTest {
    @Test
    fun `written row is read back after reopening the file`() {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            openDatabase(path).use { db ->
                db.execSQL("CREATE TABLE smoke (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
                db.prepare("INSERT INTO smoke (id, label) VALUES (?, ?)").use { stmt ->
                    stmt.bindLong(1, 1)
                    stmt.bindText(2, "Привіт")
                    stmt.step()
                }
            }

            openDatabase(path).use { db ->
                db.prepare("SELECT id, label FROM smoke").use { stmt ->
                    assertEquals(true, stmt.step())
                    assertEquals(1L, stmt.getLong(0))
                    assertEquals("Привіт", stmt.getText(1))
                    assertEquals(false, stmt.step())
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }
}
