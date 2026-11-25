package app.servicecalendar.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/** Runs [block] in BEGIN IMMEDIATE ... COMMIT, rolling back on any exception. Not reentrant: a nested call fails before running its block. */
fun <T> SQLiteConnection.transaction(block: () -> T): T {
    execSQL("BEGIN IMMEDIATE")
    val result = try {
        block()
    } catch (e: Throwable) {
        try {
            execSQL("ROLLBACK")
        } catch (rollbackFailure: Throwable) {
            e.addSuppressed(rollbackFailure)
        }
        throw e
    }
    try {
        execSQL("COMMIT")
    } catch (e: Throwable) {
        try {
            execSQL("ROLLBACK")
        } catch (rollbackFailure: Throwable) {
            e.addSuppressed(rollbackFailure)
        }
        throw e
    }
    return result
}

fun SQLiteConnection.execute(sql: String, binder: SQLiteStatement.() -> Unit = {}) {
    prepare(sql).use { stmt ->
        stmt.binder()
        stmt.step()
    }
}

fun <T> SQLiteConnection.query(
    sql: String,
    binder: SQLiteStatement.() -> Unit = {},
    mapper: (SQLiteStatement) -> T,
): List<T> =
    prepare(sql).use { stmt ->
        stmt.binder()
        val rows = mutableListOf<T>()
        while (stmt.step()) rows += mapper(stmt)
        rows
    }

fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

fun SQLiteStatement.bindLongOrNull(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

fun SQLiteStatement.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

fun SQLiteStatement.getTextOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
