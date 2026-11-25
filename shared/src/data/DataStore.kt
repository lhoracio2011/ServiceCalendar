package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.servicecalendar.db.execute
import app.servicecalendar.db.openAppDatabase
import app.servicecalendar.db.readDataGeneration
import app.servicecalendar.db.transaction
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.StorageBusy
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sole owner of the SQLite connection. One Mutex covers every read and write because the connection is not thread-safe.
 * Writes that arrive during [restore] fail fast with [StorageBusy] (a write without a generation check would land on restored data); reads wait.
 * Nested access from inside a block is rejected, since the Mutex is not reentrant.
 */
@OptIn(ExperimentalAtomicApi::class)
class DataStore(
    private val databaseName: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    private var connection: SQLiteConnection? = null
    private var closed = false

    private val _revision = MutableStateFlow(0L)

    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)

    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _dataGeneration = MutableStateFlow<Long?>(null)

    val dataGeneration: StateFlow<Long?> = _dataGeneration.asStateFlow()

    private val committedRestores = AtomicLong(0L)

    suspend fun <T> read(block: (SQLiteConnection) -> T): T = access { db ->
        db.execSQL("PRAGMA query_only = ON")
        try {
            db.execSQL("BEGIN DEFERRED")
            try {
                block.inScope(db).also { db.execSQL("COMMIT") }
            } catch (e: Throwable) {
                db.rollbackInto(e)
                throw e
            }
        } finally {
            db.execSQL("PRAGMA query_only = OFF")
        }
    }

    suspend fun <T> write(block: (SQLiteConnection) -> T): T {
        checkNotNested()
        val restoresAtCall = committedRestores.load()
        if (_isRestoring.value) throw StorageBusy()
        return access { db ->
            // A restore committed after this call was queued: reject, otherwise the write would run against the restored data set.
            if (committedRestores.load() != restoresAtCall) throw StaleGeneration()
            val (result, generation) = db.transaction { block.inScope(db) to db.readDataGeneration() }
            _dataGeneration.value = generation
            _revision.value += 1
            result
        }
    }

    /** Replaces the data set and increments dataGeneration in the same transaction; the new generation and revision are published before the Mutex is released. */
    suspend fun <T> restore(block: (SQLiteConnection) -> T): T {
        checkNotNested()
        if (!_isRestoring.compareAndSet(expect = false, update = true)) throw StorageBusy()
        try {
            return access { db ->
                val (result, generation) = db.transaction {
                    val before = db.readDataGeneration()
                    val replaced = block.inScope(db)
                    db.execute("UPDATE device_state SET dataGeneration = ? WHERE id = 1") { bindLong(1, before + 1) }
                    val after = db.readDataGeneration()
                    check(after == before + 1) { "Data generation did not increase during restore" }
                    replaced to after
                }
                committedRestores.incrementAndFetch()
                _dataGeneration.value = generation
                _revision.value += 1
                result
            }
        } finally {
            _isRestoring.value = false
        }
    }

    suspend fun currentGeneration(): Long = read { it.readDataGeneration() }

    suspend fun close() {
        checkNotNested()
        mutex.withLock {
            closed = true
            val db = connection ?: return
            connection = null
            db.close()
        }
    }

    private suspend fun <T> access(action: (SQLiteConnection) -> T): T {
        checkNotNested()
        return withContext(dispatcher) {
            mutex.withLock {
                val db = openIfNeeded()
                val previousOwner = currentDataStoreBlockOwner
                currentDataStoreBlockOwner = this@DataStore
                try {
                    action(db)
                } finally {
                    currentDataStoreBlockOwner = previousOwner
                }
            }
        }
    }

    private fun checkNotNested() {
        if (currentDataStoreBlockOwner === this) throw NestedDataStoreAccessException()
    }

    private fun openIfNeeded(): SQLiteConnection {
        check(!closed) { "DataStore is closed" }
        connection?.let { return it }
        val db = openAppDatabase(databaseName)
        try {
            _dataGeneration.value = db.readDataGeneration()
        } catch (e: Throwable) {
            try {
                db.close()
            } catch (closeFailure: Throwable) {
                e.addSuppressed(closeFailure)
            }
            throw e
        }
        connection = db
        return db
    }

    private fun <T> ((SQLiteConnection) -> T).inScope(db: SQLiteConnection): T {
        val scoped = ScopedConnection(db)
        try {
            return this(scoped)
        } finally {
            scoped.active = false
        }
    }

    private fun SQLiteConnection.rollbackInto(failure: Throwable) {
        try {
            execSQL("ROLLBACK")
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }
}

class NestedDataStoreAccessException : IllegalStateException(
    "Nested DataStore access from a read/write/restore block is not allowed: do everything in one block",
)

private class ScopedConnection(private val delegate: SQLiteConnection) : SQLiteConnection {
    var active = true

    override fun prepare(sql: String): SQLiteStatement {
        check(active) { "DataStore connection used outside a read/write block" }
        return delegate.prepare(sql)
    }

    override fun inTransaction(): Boolean {
        check(active) { "DataStore connection used outside a read/write block" }
        return delegate.inTransaction()
    }

    override fun close() {
        throw IllegalStateException("The connection is owned by DataStore: it cannot be closed from a block")
    }
}

internal expect var currentDataStoreBlockOwner: Any?
