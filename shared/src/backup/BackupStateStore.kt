package app.servicecalendar.backup

import androidx.sqlite.SQLiteConnection
import app.servicecalendar.data.DataStore
import app.servicecalendar.db.bindLongOrNull
import app.servicecalendar.db.bindTextOrNull
import app.servicecalendar.db.execute
import app.servicecalendar.db.getLongOrNull
import app.servicecalendar.db.getTextOrNull
import app.servicecalendar.db.query
import app.servicecalendar.db.readDataGeneration
import kotlin.time.Instant

enum class BackupSearchState {
    NOT_SEARCHED,

    SEARCHING,

    FOUND_NONE,

    FOUND_COPY,

    FAILED,
}

enum class BackupChoice {
    NONE,

    RESTORE,

    REPLACE_CONFIRMED,

    POSTPONED,
}

data class BackupBinding(val accountId: String, val dataGeneration: Long) {
    init {
        require(accountId.isNotBlank()) { "Account ID cannot be blank" }
        require(dataGeneration >= 1) { "Data generation starts at 1: $dataGeneration" }
    }

    override fun toString(): String = "BackupBinding(account=***, dataGeneration=$dataGeneration)"
}

/** Device-local state: never part of a snapshot and not replaced by restore. The constructor enforces invariants, so an inconsistent state cannot be built. */
data class BackupState(
    val accountId: String? = null,
    val searchState: BackupSearchState = BackupSearchState.NOT_SEARCHED,
    val choice: BackupChoice = BackupChoice.NONE,
    val driveFileId: String? = null,
    val lastSuccessfulBackupAt: Instant? = null,
    val boundGeneration: Long? = null,
    val remoteRevision: String? = null,
) {
    init {
        require((accountId == null) == (boundGeneration == null)) {
            "Account and binding generation must be set together"
        }
        require(accountId == null || accountId.isNotBlank()) { "Account ID cannot be blank" }
        require(boundGeneration == null || boundGeneration >= 1) { "Data generation starts at 1" }
        require(driveFileId == null || driveFileId.isNotBlank()) { "Drive file ID cannot be blank" }
        require(remoteRevision == null || (remoteRevision.isNotBlank() && driveFileId != null)) {
            "Backup revision tag must be non-blank and set together with the file ID"
        }
        if (accountId == null) {
            require(searchState == BackupSearchState.NOT_SEARCHED && driveFileId == null && lastSuccessfulBackupAt == null) {
                "Without an account only the initial state is possible"
            }
        }
        require(choice == BackupChoice.NONE || searchState == BackupSearchState.FOUND_COPY) {
            "Choice $choice is only possible for a found backup, but search state is $searchState"
        }
        when (searchState) {
            BackupSearchState.FOUND_COPY -> require(driveFileId != null) { "A found backup requires a file ID" }
            BackupSearchState.FOUND_NONE -> Unit
            else -> require(driveFileId == null) { "File ID is unknown until the search completes" }
        }
    }

    val dailyBackupsAllowed: Boolean
        get() = searchState == BackupSearchState.FOUND_NONE ||
            (searchState == BackupSearchState.FOUND_COPY &&
                (choice == BackupChoice.RESTORE || choice == BackupChoice.REPLACE_CONFIRMED))

    fun isBoundTo(binding: BackupBinding): Boolean =
        accountId == binding.accountId && boundGeneration == binding.dataGeneration

    override fun toString(): String =
        "BackupState(account=${if (accountId == null) "none" else "***"}, search=$searchState, choice=$choice, " +
            "file=${if (driveFileId == null) "none" else "***"}, lastSuccess=$lastSuccessfulBackupAt, " +
            "generation=$boundGeneration, revision=${if (remoteRevision == null) "none" else "***"}, " +
            "allowed=$dailyBackupsAllowed)"

    companion object {
        val INITIAL: BackupState = BackupState()

        fun freshFor(binding: BackupBinding): BackupState =
            BackupState(accountId = binding.accountId, boundGeneration = binding.dataGeneration)
    }
}

data class DeviceBackupState(val state: BackupState, val dataGeneration: Long)

class BackupStateStore(private val store: DataStore) {
    suspend fun load(): BackupState = store.read { it.readBackupState() }

    suspend fun loadWithGeneration(): DeviceBackupState =
        store.read { DeviceBackupState(it.readBackupState(), it.readDataGeneration()) }

    suspend fun save(state: BackupState) {
        store.write { it.writeBackupState(state) }
    }

    /** Atomic read-transform-write under the store's Mutex; [transform] must be pure and must not call DataStore. */
    suspend fun update(transform: (state: BackupState, dataGeneration: Long) -> BackupState): BackupState =
        store.write { db ->
            val current = db.readBackupState()
            val next = transform(current, db.readDataGeneration())
            if (next != current) db.writeBackupState(next)
            next
        }

    /**
     * [update] for a finished upload: if the state changes, [dataHash] (see [SnapshotCodec.dataFingerprint]) is stored
     * in the same transaction as the fingerprint of the data now on Drive.
     */
    suspend fun recordUpload(
        dataHash: String,
        transform: (state: BackupState, dataGeneration: Long) -> BackupState,
    ): BackupState =
        store.write { db ->
            val current = db.readBackupState()
            val next = transform(current, db.readDataGeneration())
            if (next != current) {
                db.writeBackupState(next)
                db.writeBackupDataHash(dataHash)
            }
            next
        }

    /** True unless the local data set is known to equal the one last uploaded or restored (no fingerprint means unknown). */
    suspend fun dataChangedSinceBackup(): Boolean =
        store.read { db ->
            val stored = db.readBackupDataHash()
            stored == null || stored != SnapshotCodec.dataFingerprint(SnapshotCodec.readSnapshot(db, createdAt = ""))
        }
}

fun SQLiteConnection.readBackupDataHash(): String? =
    query("SELECT backupDataHash FROM device_state WHERE id = 1", mapper = { it.getTextOrNull(0) }).singleOrNull()

fun SQLiteConnection.writeBackupDataHash(dataHash: String?) {
    execute("UPDATE device_state SET backupDataHash = ? WHERE id = 1") { bindTextOrNull(1, dataHash) }
}

fun SQLiteConnection.readBackupState(): BackupState =
    query(
        "SELECT backupAccountId, backupSearchState, backupChoice, backupDriveFileId, backupLastSuccessAt, " +
            "backupBoundGeneration, backupDailyAllowed, backupRemoteRevision FROM device_state WHERE id = 1",
        mapper = { row ->
            val state = BackupState(
                accountId = row.getTextOrNull(0),
                searchState = searchStateFromDb(row.getText(1)),
                choice = choiceFromDb(row.getText(2)),
                driveFileId = row.getTextOrNull(3),
                lastSuccessfulBackupAt = row.getTextOrNull(4)?.let(Instant::parse),
                boundGeneration = row.getLongOrNull(5),
                remoteRevision = row.getTextOrNull(7),
            )
            check((row.getLong(6) == 1L) == state.dailyBackupsAllowed) {
                "device_state: stored backup permission does not match state $state"
            }
            state
        },
    ).singleOrNull() ?: error("device_state has no device state row")

fun SQLiteConnection.writeBackupState(state: BackupState) {
    execute(
        "UPDATE device_state SET backupAccountId = ?, backupSearchState = ?, backupChoice = ?, " +
            "backupDriveFileId = ?, backupLastSuccessAt = ?, backupBoundGeneration = ?, backupDailyAllowed = ?, " +
            "backupRemoteRevision = ? WHERE id = 1",
    ) {
        bindTextOrNull(1, state.accountId)
        bindText(2, state.searchState.dbValue)
        bindText(3, state.choice.dbValue)
        bindTextOrNull(4, state.driveFileId)
        bindTextOrNull(5, state.lastSuccessfulBackupAt?.toString())
        bindLongOrNull(6, state.boundGeneration)
        bindLong(7, if (state.dailyBackupsAllowed) 1 else 0)
        bindTextOrNull(8, state.remoteRevision)
    }
}

/** Enums are persisted by explicit codes, so renaming a Kotlin constant does not break stored data. */
private val BackupSearchState.dbValue: String
    get() = when (this) {
        BackupSearchState.NOT_SEARCHED -> "not_searched"
        BackupSearchState.SEARCHING -> "searching"
        BackupSearchState.FOUND_NONE -> "found_none"
        BackupSearchState.FOUND_COPY -> "found_copy"
        BackupSearchState.FAILED -> "failed"
    }

private val BackupChoice.dbValue: String
    get() = when (this) {
        BackupChoice.NONE -> "none"
        BackupChoice.RESTORE -> "restore"
        BackupChoice.REPLACE_CONFIRMED -> "replace_confirmed"
        BackupChoice.POSTPONED -> "postponed"
    }

private fun searchStateFromDb(value: String): BackupSearchState =
    BackupSearchState.entries.firstOrNull { it.dbValue == value }
        ?: error("device_state: unknown search state \"$value\"")

private fun choiceFromDb(value: String): BackupChoice =
    BackupChoice.entries.firstOrNull { it.dbValue == value }
        ?: error("device_state: unknown choice \"$value\"")
