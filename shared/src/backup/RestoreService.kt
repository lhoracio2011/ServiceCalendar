package app.servicecalendar.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.servicecalendar.data.DataStore
import app.servicecalendar.db.bindLongOrNull
import app.servicecalendar.db.bindTextOrNull
import app.servicecalendar.db.execute
import app.servicecalendar.db.query
import app.servicecalendar.db.readDataGeneration
import app.servicecalendar.domain.StorageBusy
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InvalidSnapshotException(val invalid: SnapshotValidationResult.Invalid) :
    Exception("Backup failed validation: ${invalid.reason}: ${invalid.detail}")

data class DatasetCounts(
    val procedures: Int,
    val places: Int,
    val placeRates: Int,
    val workEntries: Int,
    val workLines: Int,
) {
    companion object {
        fun of(snapshot: BackupSnapshot): DatasetCounts = DatasetCounts(
            procedures = snapshot.procedures.size,
            places = snapshot.places.size,
            placeRates = snapshot.placeRates.size,
            workEntries = snapshot.workEntries.size,
            workLines = snapshot.workLines.size,
        )
    }
}

data class RestoreReport(
    val createdAt: String,
    val currencyCode: String,
    val backup: DatasetCounts,
    val local: DatasetCounts,
    val localCurrencyCode: String,
    val differsFromLocal: Boolean,
    val localGeneration: Long,
)

data class RestoreResult(
    val generation: Long,
    val restored: DatasetCounts,
)

sealed interface RestoreState {
    data object Idle : RestoreState

    data object InProgress : RestoreState

    data class Succeeded(val result: RestoreResult) : RestoreState

    data class Failed(val error: Throwable) : RestoreState
}

@OptIn(ExperimentalAtomicApi::class)
class RestoreService(private val store: DataStore) {
    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)

    val state: StateFlow<RestoreState> = _state.asStateFlow()

    suspend fun inspect(snapshot: BackupSnapshot): RestoreReport {
        val valid = requireValid(snapshot)
        val (local, generation) = readLocal()
        return RestoreReport(
            createdAt = valid.createdAt,
            currencyCode = valid.currencyCode,
            backup = DatasetCounts.of(valid),
            local = DatasetCounts.of(local),
            localCurrencyCode = local.currencyCode,
            differsFromLocal = valid.normalized() != local.normalized(),
            localGeneration = generation,
        )
    }

    /**
     * Replaces the whole data set with the snapshot, keeping original IDs, and bumps the generation in the same transaction.
     * [backupStateAfter] is written in that transaction as well, so the restore and the daily-backup permission commit together or not at all.
     */
    suspend fun restore(
        snapshot: BackupSnapshot,
        backupStateAfter: ((newGeneration: Long) -> BackupState)? = null,
    ): RestoreResult {
        if (!running.compareAndSet(expectedValue = false, newValue = true)) throw StorageBusy()
        try {
            _state.value = RestoreState.InProgress
            var expectedGeneration: Long? = null
            try {
                val valid = requireValid(snapshot)
                val generation = store.restore { db ->
                    db.replaceDataset(valid)
                    val next = db.readDataGeneration() + 1
                    backupStateAfter?.let { db.writeBackupState(it(next)) }
                    // The restored set is what the copy holds, so it counts as backed up; without a new backup state it is unknown.
                    db.writeBackupDataHash(
                        backupStateAfter?.let { SnapshotCodec.dataFingerprint(SnapshotCodec.readSnapshot(db, createdAt = "")) },
                    )
                    next.also { expectedGeneration = it }
                }
                return RestoreResult(generation, DatasetCounts.of(valid)).also {
                    _state.value = RestoreState.Succeeded(it)
                }
            } catch (e: Throwable) {
                val expected = expectedGeneration
                val committed = expected != null && store.dataGeneration.value == expected
                _state.value = when {
                    committed -> RestoreState.Succeeded(RestoreResult(checkNotNull(expected), DatasetCounts.of(snapshot)))
                    e is CancellationException -> RestoreState.Idle
                    else -> RestoreState.Failed(e)
                }
                throw e
            }
        } finally {
            running.store(false)
        }
    }

    private fun requireValid(snapshot: BackupSnapshot): BackupSnapshot =
        when (val result = validateSnapshot(snapshot)) {
            is SnapshotValidationResult.Valid -> result.snapshot
            is SnapshotValidationResult.Invalid -> throw InvalidSnapshotException(result)
        }

    private suspend fun readLocal(): Pair<BackupSnapshot, Long> {
        while (true) {
            val generation = store.currentGeneration()
            val local = SnapshotCodec.readSnapshot(store, createdAt = "")
            if (store.currentGeneration() == generation) return local to generation
        }
    }
}

// The format version is not data: a version 1 copy equal to the local data set does not count as different.
private fun BackupSnapshot.normalized(): BackupSnapshot = copy(
    schemaVersion = 0,
    createdAt = "",
    procedures = procedures.sortedBy { it.id },
    places = places.sortedBy { it.id },
    placeRates = placeRates.sortedWith(compareBy({ it.placeId }, { it.procedureId })),
    workEntries = workEntries.sortedBy { it.id },
    workLines = workLines.sortedWith(compareBy({ it.entryId }, { it.procedureId })),
)

private fun SQLiteConnection.replaceDataset(snapshot: BackupSnapshot) {
    // Delete child tables first and insert parents first, so foreign keys hold for every row.
    execSQL("DELETE FROM work_line")
    execSQL("DELETE FROM work_entry")
    execSQL("DELETE FROM place_rate")
    execSQL("DELETE FROM place")
    execSQL("DELETE FROM procedure")

    for (p in snapshot.procedures) {
        execute("INSERT INTO procedure (id, name, archived) VALUES (?, ?, ?)") {
            bindLong(1, p.id)
            bindText(2, p.name)
            bindLong(3, if (p.archived) 1 else 0)
        }
    }
    for (p in snapshot.places) {
        execute("INSERT INTO place (id, name, address, color, archived) VALUES (?, ?, ?, ?, ?)") {
            bindLong(1, p.id)
            bindText(2, p.name)
            bindTextOrNull(3, p.address)
            bindLong(4, p.color)
            bindLong(5, if (p.archived) 1 else 0)
        }
    }
    for (r in snapshot.placeRates) {
        execute("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (?, ?, ?)") {
            bindLong(1, r.placeId)
            bindLong(2, r.procedureId)
            bindLong(3, r.amountMinor)
        }
    }
    for (e in snapshot.workEntries) {
        execute("INSERT INTO work_entry (id, localDate, placeId, scheduledCount) VALUES (?, ?, ?, ?)") {
            bindLong(1, e.id)
            bindText(2, e.localDate)
            bindLong(3, e.placeId)
            bindLongOrNull(4, e.scheduledCount)
        }
    }
    for (l in snapshot.workLines) {
        execute("INSERT INTO work_line (entryId, procedureId, quantity, appliedAmountMinor) VALUES (?, ?, ?, ?)") {
            bindLong(1, l.entryId)
            bindLong(2, l.procedureId)
            bindLong(3, l.quantity)
            bindLong(4, l.appliedAmountMinor)
        }
    }

    execute("UPDATE dataset_settings SET currencyCode = ? WHERE id = 1") { bindText(1, snapshot.currencyCode) }
    val currency = query("SELECT currencyCode FROM dataset_settings WHERE id = 1") { it.getText(0) }.singleOrNull()
    check(currency == snapshot.currencyCode) { "Failed to write dataset currency" }
}
