package app.servicecalendar.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Schema (v1 to v5) and the migration runner against a real database file. */
class SchemaTest {

    private fun withPath(block: (String) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            block(path)
        } finally {
            deleteTestDatabase(path)
        }
    }

    private fun withDb(block: (SQLiteConnection) -> Unit) = withPath { path ->
        openAppDatabase(path).use(block)
    }

    private fun SQLiteConnection.longValue(sql: String): Long =
        query(sql, mapper = { it.getLong(0) }).single()

    private fun SQLiteConnection.userVersion(): Long = longValue("PRAGMA user_version")

    private fun SQLiteConnection.schemaObjects(): List<String> =
        query(
            "SELECT type || ':' || name || ':' || ifnull(sql, '') FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            mapper = { it.getText(0) },
        )

    private fun SQLiteConnection.names(type: String): Set<String> =
        query(
            "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_%'",
            binder = { bindText(1, type) },
            mapper = { it.getText(0) },
        ).toSet()

    private fun SQLiteConnection.count(table: String): Long =
        longValue("SELECT COUNT(*) FROM $table")

    private fun SQLiteConnection.insertProcedure(id: Long, name: String = "Огляд $id") =
        execute("INSERT INTO procedure (id, name) VALUES (?, ?)") {
            bindLong(1, id)
            bindText(2, name)
        }

    private fun SQLiteConnection.insertPlace(id: Long, color: Long = 0xFF000000L + id, archived: Boolean = false) =
        execute("INSERT INTO place (id, name, address, color, archived) VALUES (?, ?, NULL, ?, ?)") {
            bindLong(1, id)
            bindText(2, "Місце $id")
            bindLong(3, color)
            bindLong(4, if (archived) 1 else 0)
        }

    private fun SQLiteConnection.insertRate(placeId: Long, procedureId: Long, amountMinor: Long) =
        execute("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (?, ?, ?)") {
            bindLong(1, placeId)
            bindLong(2, procedureId)
            bindLong(3, amountMinor)
        }

    private fun SQLiteConnection.insertEntry(id: Long, localDate: String, placeId: Long) =
        execute("INSERT INTO work_entry (id, localDate, placeId) VALUES (?, ?, ?)") {
            bindLong(1, id)
            bindText(2, localDate)
            bindLong(3, placeId)
        }

    private fun SQLiteConnection.insertLine(entryId: Long, procedureId: Long, quantity: Long, appliedAmountMinor: Long) =
        execute("INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) VALUES (?, ?, ?, ?)") {
            bindLong(1, entryId)
            bindLong(2, procedureId)
            bindLong(3, quantity)
            bindLong(4, appliedAmountMinor)
        }

    /** Place 1, procedures 1 and 2, and entry 1 on 2026-11-09 at place 1. */
    private fun SQLiteConnection.seedBasics() {
        insertPlace(1)
        insertProcedure(1)
        insertProcedure(2)
        insertEntry(1, "2026-11-09", 1)
    }

    @Test
    fun `empty database migrates to the latest version with all tables and indexes`() = withDb { db ->
        assertEquals(Schema.VERSION.toLong(), db.userVersion())
        assertEquals(5, Schema.VERSION)
        assertEquals(
            setOf("procedure", "place", "place_rate", "work_entry", "work_line", "dataset_settings", "device_state"),
            db.names("table"),
        )
        val indexes = db.names("index")
        assertTrue("work_entry_date_place" in indexes, "index on work_entry(localDate, placeId): $indexes")
        assertTrue("place_active_color" !in indexes, "place color must not be unique: $indexes")
        val indexColumns = db.query("PRAGMA index_info('work_entry_date_place')", mapper = { it.getText(2) })
        assertEquals(listOf("localDate", "placeId"), indexColumns)
    }

    @Test
    fun `v1 seeds currency and data generation rows`() = withDb { db ->
        assertEquals(
            listOf("BRL"),
            db.query("SELECT currencyCode FROM dataset_settings", mapper = { it.getText(0) }),
        )
        assertEquals(listOf(1L), db.query("SELECT dataGeneration FROM device_state", mapper = { it.getLong(0) }))
    }

    @Test
    fun `single row tables reject a second row`() = withDb { db ->
        assertFailsWith<Exception> { db.execSQL("INSERT INTO dataset_settings (id, currencyCode) VALUES (2, 'USD')") }
        assertFailsWith<Exception> { db.execSQL("INSERT INTO device_state (id, dataGeneration) VALUES (2, 1)") }
        assertEquals(1L, db.count("dataset_settings"))
        assertEquals(1L, db.count("device_state"))
    }

    @Test
    fun `rerunning migrations changes nothing`() = withPath { path ->
        val before = openAppDatabase(path).use { db ->
            db.seedBasics()
            db.schemaObjects() to db.userVersion()
        }
        openAppDatabase(path).use { db ->
            db.migrate()
            db.migrate()
            assertEquals(before, db.schemaObjects() to db.userVersion())
            assertEquals(1L, db.count("work_entry"))
            assertEquals(1L, db.count("dataset_settings"))
            assertEquals(1L, db.count("device_state"))
        }
    }

    @Test
    fun `database from a newer app version is rejected untouched`() = withPath { path ->
        openDatabase(path).use { db -> db.execSQL("PRAGMA user_version = 99") }
        assertFailsWith<UnsupportedSchemaVersionException> { openAppDatabase(path) }
        openDatabase(path).use { db ->
            assertEquals(99L, db.userVersion())
            assertEquals(emptySet(), db.names("table"))
        }
    }

    @Test
    fun `failed migration step rolls back and keeps the previous version`() = withPath { path ->
        openAppDatabase(path).close()
        val broken = Schema.migrations + Migration(Schema.VERSION + 1) { db ->
            db.execSQL("CREATE TABLE extra (id INTEGER PRIMARY KEY)")
            error("migration failure")
        }
        openDatabase(path).use { db ->
            assertFailsWith<IllegalStateException> { db.migrate(broken) }
            assertEquals(Schema.VERSION.toLong(), db.userVersion())
            assertTrue("extra" !in db.names("table"))
            // The connection is still usable after the failed step.
            db.transaction { db.insertProcedure(1) }
        }
    }

    @Test
    fun `v1 database migrates to v2 with data intact and backup defaults`() = withPath { path ->
        // A database created by an app version with schema v1.
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(1))
            assertEquals(1L, db.userVersion())
            db.seedBasics()
            db.insertRate(1, 1, 1250)
            db.insertLine(1, 1, 2, 1250)
            db.execSQL("UPDATE dataset_settings SET currencyCode = 'USD'")
            db.execSQL("UPDATE device_state SET dataGeneration = 3")
        }
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(2))
            assertEquals(2L, db.userVersion())
            assertEquals(1L, db.count("place"))
            assertEquals(2L, db.count("procedure"))
            assertEquals(1L, db.count("place_rate"))
            assertEquals(1L, db.count("work_entry"))
            assertEquals(1L, db.count("work_line"))
            assertEquals("USD", db.query("SELECT currencyCode FROM dataset_settings") { it.getText(0) }.single())
            assertEquals(1L, db.count("device_state"))
            val row = db.query(
                "SELECT dataGeneration, backupAccountId, backupSearchState, backupChoice, backupDailyAllowed, " +
                    "backupDriveFileId, backupLastSuccessAt, backupBoundGeneration FROM device_state",
                mapper = { stmt -> (0 until 8).map { if (stmt.isNull(it)) null else stmt.getText(it) } },
            ).single()
            assertEquals(listOf("3", null, "not_searched", "none", "0", null, null, null), row)
        }
    }

    @Test
    fun `v2 database migrates to v3 keeping the backup state and an unknown revision`() = withPath { path ->
        // Schema v2 database where daily backups are already allowed for the user's own Drive copy.
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(2))
            assertEquals(2L, db.userVersion())
            db.seedBasics()
            db.execSQL(
                "UPDATE device_state SET backupAccountId = 'a', backupBoundGeneration = 1, " +
                    "backupSearchState = 'found_none', backupDriveFileId = 'drive-file-1', " +
                    "backupLastSuccessAt = '2026-11-10T12:00:00Z', backupDailyAllowed = 1",
            )
        }
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(3))
            assertEquals(3L, db.userVersion())
            assertEquals(1L, db.count("work_entry"))
            val row = db.query(
                "SELECT backupAccountId, backupSearchState, backupChoice, backupDailyAllowed, backupDriveFileId, " +
                    "backupLastSuccessAt, backupBoundGeneration, backupRemoteRevision FROM device_state",
                mapper = { stmt -> (0 until 8).map { if (stmt.isNull(it)) null else stmt.getText(it) } },
            ).single()
            // The revision is unknown (NULL): the coordinator checks the copy by modified time before the first update.
            assertEquals(listOf("a", "found_none", "none", "1", "drive-file-1", "2026-11-10T12:00:00Z", "1", null), row)
        }
    }

    @Test
    fun `v3 database migrates to v4 with entries and lines intact and no scheduled count`() = withPath { path ->
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(3))
            assertEquals(3L, db.userVersion())
            db.seedBasics()
            db.insertEntry(2, "2026-11-10", 1)
            db.insertRate(1, 1, 1250)
            db.insertLine(1, 1, 2, 1250)
            db.insertLine(1, 2, 3, 2000)
            db.insertLine(2, 1, 1, 1000)
            db.execSQL("UPDATE device_state SET dataGeneration = 5, backupDriveFileId = 'f', backupRemoteRevision = 'rev-1'")
        }
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(4))
            assertEquals(4L, db.userVersion())
            assertEquals(
                listOf("1|2026-11-09|1|null", "2|2026-11-10|1|null"),
                db.query(
                    "SELECT id, localDate, placeId, scheduledCount FROM work_entry ORDER BY id",
                    mapper = { "${it.getLong(0)}|${it.getText(1)}|${it.getLong(2)}|${it.getLongOrNull(3)}" },
                ),
            )
            assertEquals(
                listOf("1|1|2|1250", "1|2|3|2000", "2|1|1|1000"),
                db.query(
                    "SELECT entryId, procedureId, quantity, appliedAmountMinor FROM work_line ORDER BY entryId, procedureId",
                    mapper = { "${it.getLong(0)}|${it.getLong(1)}|${it.getLong(2)}|${it.getLong(3)}" },
                ),
            )
            assertEquals(1L, db.count("place_rate"))
            assertEquals(5L, db.longValue("SELECT dataGeneration FROM device_state"))
            assertEquals("rev-1", db.query("SELECT backupRemoteRevision FROM device_state") { it.getText(0) }.single())
        }
    }

    @Test
    fun `v4 database migrates to v5 keeping data and backup state with an unknown data fingerprint`() = withPath { path ->
        openDatabase(path).use { db ->
            db.migrate(Schema.migrations.take(4))
            db.seedBasics()
            db.insertRate(1, 1, 1250)
            db.insertLine(1, 1, 2, 1250)
            db.execSQL("UPDATE work_entry SET scheduledCount = 7")
            db.execSQL(
                "UPDATE device_state SET dataGeneration = 3, backupAccountId = 'acc', backupBoundGeneration = 3, " +
                    "backupSearchState = 'found_none', backupDriveFileId = 'f', backupRemoteRevision = 'rev-1', " +
                    "backupLastSuccessAt = '2026-11-20T10:00:00Z', backupDailyAllowed = 1",
            )
        }
        openAppDatabase(path).use { db ->
            assertEquals(5L, db.userVersion())
            assertEquals(1L, db.count("place_rate"))
            assertEquals(7L, db.longValue("SELECT scheduledCount FROM work_entry"))
            assertEquals(
                listOf("acc|f|rev-1|2026-11-20T10:00:00Z|1|true"),
                db.query(
                    "SELECT backupAccountId, backupDriveFileId, backupRemoteRevision, backupLastSuccessAt, " +
                        "backupDailyAllowed, backupDataHash IS NULL FROM device_state",
                    mapper = {
                        "${it.getText(0)}|${it.getText(1)}|${it.getText(2)}|${it.getText(3)}|${it.getLong(4)}|${it.getLong(5) == 1L}"
                    },
                ),
            )
            assertFailsWith<Exception> { db.execSQL("UPDATE device_state SET backupDataHash = ''") }
            assertFailsWith<Exception> { db.execSQL("UPDATE device_state SET backupDataHash = X'01'") }
            db.execSQL("UPDATE device_state SET backupDataHash = 'fnv1a64:0123456789abcdef:10'")
        }
    }

    @Test
    fun `scheduled count is null or an integer within Int range`() = withDb { db ->
        db.seedBasics()
        listOf(
            "UPDATE work_entry SET scheduledCount = -1",
            "UPDATE work_entry SET scheduledCount = ${Int.MAX_VALUE.toLong() + 1}",
            "UPDATE work_entry SET scheduledCount = 1.5",
            "UPDATE work_entry SET scheduledCount = 'ten'",
        ).forEach { sql -> assertFailsWith<Exception>(sql) { db.execSQL(sql) } }
        db.execSQL("UPDATE work_entry SET scheduledCount = 0")
        assertEquals(0L, db.longValue("SELECT scheduledCount FROM work_entry"))
        db.execSQL("UPDATE work_entry SET scheduledCount = ${Int.MAX_VALUE}")
        assertEquals(Int.MAX_VALUE.toLong(), db.longValue("SELECT scheduledCount FROM work_entry"))
        db.execSQL("UPDATE work_entry SET scheduledCount = NULL")
        assertEquals(1L, db.longValue("SELECT scheduledCount IS NULL FROM work_entry"))
    }

    @Test
    fun `remote revision needs a drive file id and is non-empty text`() = withDb { db ->
        listOf(
            "UPDATE device_state SET backupRemoteRevision = 'rev-1'",
            "UPDATE device_state SET backupDriveFileId = 'f', backupRemoteRevision = ''",
            "UPDATE device_state SET backupDriveFileId = 'f', backupRemoteRevision = x'00'",
        ).forEach { sql -> assertFailsWith<Exception>(sql) { db.execSQL(sql) } }
        db.execSQL("UPDATE device_state SET backupDriveFileId = 'f', backupRemoteRevision = 'rev-1'")
        assertEquals(
            listOf("rev-1"),
            db.query("SELECT backupRemoteRevision FROM device_state", mapper = { it.getText(0) }),
        )
    }

    @Test
    fun `backup columns stay in their domain`() = withDb { db ->
        listOf(
            "UPDATE device_state SET backupSearchState = 'done'",
            "UPDATE device_state SET backupSearchState = NULL",
            "UPDATE device_state SET backupChoice = 'later'",
            "UPDATE device_state SET backupDailyAllowed = 2",
            // Daily permission without an account, binding and a finished search.
            "UPDATE device_state SET backupDailyAllowed = 1",
            "UPDATE device_state SET backupAccountId = ''",
            // SQLite converts a number in a TEXT column to text, so that case is not checked.
            "UPDATE device_state SET backupAccountId = x'00'",
            "UPDATE device_state SET backupDriveFileId = ''",
            "UPDATE device_state SET backupBoundGeneration = 0",
            "UPDATE device_state SET backupBoundGeneration = 'one'",
            "UPDATE device_state SET backupLastSuccessAt = '10.11.2026'",
        ).forEach { sql -> assertFailsWith<Exception>(sql) { db.execSQL(sql) } }

        db.execSQL(
            "UPDATE device_state SET backupAccountId = 'a', backupBoundGeneration = 1, " +
                "backupSearchState = 'found_none', backupDailyAllowed = 1, " +
                "backupLastSuccessAt = '2026-11-10T12:00:00Z'",
        )
        assertEquals(1L, db.longValue("SELECT backupDailyAllowed FROM device_state"))
    }

    @Test
    fun `migration list must be contiguous from one`() = withPath { path ->
        openDatabase(path).use { db ->
            assertFailsWith<IllegalArgumentException> { db.migrate(listOf(Migration(2) {})) }
            assertEquals(0L, db.userVersion())
        }
    }

    @Test
    fun `foreign keys are enabled on every open`() = withPath { path ->
        openAppDatabase(path).use { db -> assertEquals(1L, db.longValue("PRAGMA foreign_keys")) }
        openAppDatabase(path).use { db -> assertEquals(1L, db.longValue("PRAGMA foreign_keys")) }
    }

    @Test
    fun `migrate inside an open transaction fails because foreign keys cannot be enabled`() = withPath { path ->
        openDatabase(path).use { db ->
            db.execSQL("BEGIN")
            assertFailsWith<IllegalStateException> { db.migrate() }
            db.execSQL("ROLLBACK")
            assertEquals(0L, db.userVersion())
        }
    }

    @Test
    fun `references to missing rows are rejected`() = withDb { db ->
        db.insertPlace(1)
        db.insertProcedure(1)
        assertFailsWith<Exception> { db.insertRate(placeId = 1, procedureId = 99, amountMinor = 100) }
        assertFailsWith<Exception> { db.insertRate(placeId = 99, procedureId = 1, amountMinor = 100) }
        assertFailsWith<Exception> { db.insertEntry(1, "2026-11-09", placeId = 99) }
        db.insertEntry(1, "2026-11-09", 1)
        assertFailsWith<Exception> { db.insertLine(entryId = 99, procedureId = 1, quantity = 1, appliedAmountMinor = 1) }
        assertFailsWith<Exception> { db.insertLine(entryId = 1, procedureId = 99, quantity = 1, appliedAmountMinor = 1) }
        assertEquals(0L, db.count("place_rate"))
        assertEquals(0L, db.count("work_line"))
    }

    @Test
    fun `referenced place and procedure cannot be deleted`() = withDb { db ->
        db.seedBasics()
        db.insertLine(1, 1, 1, 1250)
        assertFailsWith<Exception> { db.execSQL("DELETE FROM place WHERE id = 1") }
        assertFailsWith<Exception> { db.execSQL("DELETE FROM procedure WHERE id = 1") }
        assertEquals(1L, db.count("place"))
    }

    @Test
    fun `deleting a work entry removes its lines`() = withDb { db ->
        db.seedBasics()
        db.insertLine(1, 1, 2, 1250)
        db.insertLine(1, 2, 1, 2000)
        db.execSQL("DELETE FROM work_entry WHERE id = 1")
        assertEquals(0L, db.count("work_line"))
    }

    @Test
    fun `place rate pair is unique`() = withDb { db ->
        db.seedBasics()
        db.insertRate(1, 1, 1250)
        db.insertRate(1, 2, 2000)
        assertFailsWith<Exception> { db.insertRate(1, 1, 3000) }
        assertEquals(listOf(1250L), db.query("SELECT amountMinor FROM place_rate WHERE procedureId = 1") { it.getLong(0) })
    }

    @Test
    fun `work entry date and place pair is unique`() = withDb { db ->
        db.seedBasics()
        db.insertPlace(2)
        db.insertEntry(2, "2026-11-09", 2)
        db.insertEntry(3, "2026-11-10", 1)
        assertFailsWith<Exception> { db.insertEntry(4, "2026-11-09", 1) }
        assertEquals(3L, db.count("work_entry"))
    }

    @Test
    fun `work line entry and procedure pair is unique`() = withDb { db ->
        db.seedBasics()
        db.insertLine(1, 1, 1, 1250)
        db.insertLine(1, 2, 1, 2000)
        assertFailsWith<Exception> { db.insertLine(1, 1, 5, 1250) }
        assertEquals(2L, db.count("work_line"))
    }

    @Test
    fun `amounts must be non-negative integers`() = withDb { db ->
        db.seedBasics()
        db.insertRate(1, 1, 0)
        assertFailsWith<Exception> { db.insertRate(1, 2, -1) }
        assertFailsWith<Exception> {
            db.execute("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 2, ?)") { bindText(1, "12.50") }
        }
        assertFailsWith<Exception> {
            db.execute("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (1, 2, ?)") { bindDouble(1, 12.5) }
        }
        db.insertLine(1, 1, 1, 0)
        assertFailsWith<Exception> { db.insertLine(1, 2, 1, -1) }
        assertFailsWith<Exception> { db.execSQL("UPDATE place_rate SET amountMinor = -5") }
        assertEquals(listOf(0L), db.query("SELECT amountMinor FROM place_rate") { it.getLong(0) })
    }

    @Test
    fun `quantity must be a positive integer within Int range`() = withDb { db ->
        db.seedBasics()
        assertFailsWith<Exception> { db.insertLine(1, 1, 0, 1250) }
        assertFailsWith<Exception> { db.insertLine(1, 1, -1, 1250) }
        assertFailsWith<Exception> { db.insertLine(1, 1, Int.MAX_VALUE.toLong() + 1, 1250) }
        assertFailsWith<Exception> {
            db.execute("INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) VALUES (1, 1, ?, 1)") {
                bindDouble(1, 1.5)
            }
        }
        db.insertLine(1, 1, Int.MAX_VALUE.toLong(), 1250)
        assertFailsWith<Exception> { db.execSQL("UPDATE work_line SET quantity = 0") }
        assertEquals(1L, db.count("work_line"))
    }

    @Test
    fun `local date must be a real calendar date in YYYY-MM-DD`() = withDb { db ->
        db.insertPlace(1)
        db.insertEntry(1, "2024-02-29", 1)
        listOf("2026-02-30", "2026-13-01", "2026-1-09", "09.11.2026", "2026-11-09T10:00", "").forEachIndexed { i, bad ->
            assertFailsWith<Exception>("date \"$bad\"") { db.insertEntry(10L + i, bad, 1) }
        }
        assertEquals(1L, db.count("work_entry"))
    }

    @Test
    fun `names must not be blank`() = withDb { db ->
        assertFailsWith<Exception> { db.insertProcedure(1, name = "") }
        assertFailsWith<Exception> { db.insertProcedure(1, name = "   ") }
        assertFailsWith<Exception> { db.execSQL("INSERT INTO place (id, name, color) VALUES (1, ' ', 4278190080)") }
        assertEquals(0L, db.count("procedure"))
        assertEquals(0L, db.count("place"))
    }

    @Test
    fun `archived flags and color stay in their domain`() = withDb { db ->
        assertFailsWith<Exception> { db.execSQL("INSERT INTO procedure (id, name, archived) VALUES (1, 'a', 2)") }
        assertFailsWith<Exception> { db.execSQL("INSERT INTO place (id, name, color, archived) VALUES (1, 'a', 1, 2)") }
        assertFailsWith<Exception> { db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'a', -1)") }
        assertFailsWith<Exception> { db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'a', 4294967296)") }
        db.execSQL("INSERT INTO place (id, name, color) VALUES (1, 'a', 4294967295)")
        db.execSQL("INSERT INTO procedure (id, name) VALUES (1, 'a')")
        assertEquals(0L, db.longValue("SELECT archived FROM procedure"))
        assertEquals(0L, db.longValue("SELECT archived FROM place"))
    }

    @Test
    fun `currency code is three uppercase letters`() = withDb { db ->
        listOf("brl", "BR", "BRLL", "", "R$ ").forEach { bad ->
            assertFailsWith<Exception>("code \"$bad\"") {
                db.execute("UPDATE dataset_settings SET currencyCode = ?") { bindText(1, bad) }
            }
        }
        db.execute("UPDATE dataset_settings SET currencyCode = ?") { bindText(1, "USD") }
        assertEquals("USD", db.query("SELECT currencyCode FROM dataset_settings") { it.getText(0) }.single())
    }

    @Test
    fun `data generation is a positive integer`() = withDb { db ->
        assertFailsWith<Exception> { db.execSQL("UPDATE device_state SET dataGeneration = 0") }
        assertFailsWith<Exception> { db.execSQL("UPDATE device_state SET dataGeneration = 'two'") }
        db.execSQL("UPDATE device_state SET dataGeneration = dataGeneration + 1")
        assertEquals(2L, db.longValue("SELECT dataGeneration FROM device_state"))
    }
}
