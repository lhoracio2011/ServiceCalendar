package app.servicecalendar.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SqlTest {
    private class Boom : RuntimeException("boom")

    private fun withDb(block: (SQLiteConnection) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            openDatabase(path).use { db ->
                db.execSQL("CREATE TABLE x (id INTEGER PRIMARY KEY, name TEXT, qty INTEGER)")
                block(db)
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    private fun SQLiteConnection.insert(id: Long, name: String?, qty: Long?) =
        execute("INSERT INTO x (id, name, qty) VALUES (?, ?, ?)") {
            bindLong(1, id)
            bindTextOrNull(2, name)
            bindLongOrNull(3, qty)
        }

    private fun SQLiteConnection.count(): Long =
        query("SELECT COUNT(*) FROM x", mapper = { it.getLong(0) }).single()

    @Test
    fun `commit persists changes and returns block result`() = withDb { db ->
        val result = db.transaction {
            db.insert(1, "a", 1)
            db.insert(2, "b", 2)
            "done"
        }
        assertEquals("done", result)
        assertEquals(2L, db.count())
    }

    @Test
    fun `exception rolls back and is rethrown unchanged`() = withDb { db ->
        db.insert(1, "keep", 1)
        val boom = Boom()
        val thrown = assertFailsWith<Boom> {
            db.transaction {
                db.insert(2, "lost", 2)
                throw boom
            }
        }
        assertSame(boom, thrown)
        assertEquals(1L, db.count())
        // The connection is still usable after the rollback.
        db.transaction { db.insert(3, "after", 3) }
        assertEquals(2L, db.count())
    }

    @Test
    fun `nested transaction fails and does not break the outer one`() = withDb { db ->
        assertFailsWith<Exception> {
            db.transaction {
                db.insert(1, "outer", 1)
                db.transaction { db.insert(2, "inner", 2) }
            }
        }
        assertEquals(0L, db.count())
    }

    @Test
    fun `parameters with quotes and SQL are stored literally`() = withDb { db ->
        val nasty = "O'Brien'; DROP TABLE x;--"
        db.insert(1, nasty, 1)
        val names = db.query(
            "SELECT name FROM x WHERE name = ?",
            binder = { bindText(1, nasty) },
            mapper = { it.getText(0) },
        )
        assertEquals(listOf(nasty), names)
        assertEquals(1L, db.count())
    }

    @Test
    fun `null values round-trip`() = withDb { db ->
        db.insert(1, null, null)
        db.insert(2, "n", 5)
        val rows = db.query("SELECT name, qty FROM x ORDER BY id") {
            it.getTextOrNull(0) to it.getLongOrNull(1)
        }
        assertEquals(listOf<Pair<String?, Long?>>(null to null, "n" to 5L), rows)
        assertNull(rows[0].first)
    }
}
