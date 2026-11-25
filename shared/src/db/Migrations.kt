package app.servicecalendar.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class Migration(val toVersion: Int, val apply: (SQLiteConnection) -> Unit)

class UnsupportedSchemaVersionException(val found: Int, val supported: Int) :
    IllegalStateException("Database schema version $found is newer than supported $supported")

fun openAppDatabase(name: String): SQLiteConnection {
    val db = openDatabase(name)
    try {
        db.migrate()
    } catch (e: Throwable) {
        try {
            db.close()
        } catch (closeFailure: Throwable) {
            e.addSuppressed(closeFailure)
        }
        throw e
    }
    return db
}

fun SQLiteConnection.enableForeignKeys() {
    // SQLite silently ignores this pragma inside a transaction, so the value is read back and verified.
    execSQL("PRAGMA foreign_keys = ON")
    val enabled = query("PRAGMA foreign_keys", mapper = { it.getLong(0) }).singleOrNull()
    check(enabled == 1L) {
        "Failed to enable foreign keys (in particular, impossible inside an open transaction)"
    }
}

fun SQLiteConnection.migrate(migrations: List<Migration> = Schema.migrations) {
    migrations.forEachIndexed { index, migration ->
        require(migration.toVersion == index + 1) {
            "Migration steps must be consecutive from 1: at position $index found version ${migration.toVersion}"
        }
    }
    enableForeignKeys()

    val latest = migrations.size
    val current = userVersion()
    if (current > latest) throw UnsupportedSchemaVersionException(current, latest)

    for (migration in migrations.drop(current)) {
        // Each step runs in its own transaction together with the user_version bump and a foreign_key_check, so a failure leaves the previous version intact.
        transaction {
            val before = userVersion()
            check(before == migration.toVersion - 1) {
                "Expected version ${migration.toVersion - 1}, database has $before"
            }
            migration.apply(this)
            val violations = query("PRAGMA foreign_key_check", mapper = { it.getText(0) })
            check(violations.isEmpty()) {
                "Migration to v${migration.toVersion} violated foreign keys in tables ${violations.toSet()}"
            }
            execSQL("PRAGMA user_version = ${migration.toVersion}")
        }
    }
}

private fun SQLiteConnection.userVersion(): Int =
    query("PRAGMA user_version", mapper = { it.getLong(0).toInt() }).single()
