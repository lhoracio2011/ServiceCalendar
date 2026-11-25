package app.servicecalendar.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Schema {
    // Never edit a released migration step; add a new one with the next version number.
    const val VERSION: Int = 5

    val migrations: List<Migration> = listOf(
        Migration(1) { db -> V1.forEach(db::execSQL) },
        Migration(2) { db -> V2.forEach(db::execSQL) },
        Migration(3) { db -> V3.forEach(db::execSQL) },
        Migration(4) { db -> V4.forEach(db::execSQL) },
        Migration(5) { db -> V5.forEach(db::execSQL) },
    )

    private const val INT_MAX = 2147483647L
    private const val ARGB_MAX = 4294967295L

    // AUTOINCREMENT keeps IDs from being reused after deletion, so a stale editor cannot target a different row.
    // Amount CHECKs use typeof(...) = 'integer' because INTEGER affinity would otherwise accept text and fractions.
    private val V1: List<String> = listOf(
        """
        CREATE TABLE procedure (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            name     TEXT    NOT NULL CHECK (typeof(name) = 'text' AND length(trim(name)) > 0),
            archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
        )
        """,
        """
        CREATE TABLE place (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            name     TEXT    NOT NULL CHECK (typeof(name) = 'text' AND length(trim(name)) > 0),
            address  TEXT    CHECK (address IS NULL OR typeof(address) = 'text'),
            color    INTEGER NOT NULL CHECK (typeof(color) = 'integer' AND color BETWEEN 0 AND $ARGB_MAX),
            archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
        )
        """,
        """
        CREATE TABLE place_rate (
            placeId     INTEGER NOT NULL REFERENCES place (id),
            procedureId INTEGER NOT NULL REFERENCES procedure (id),
            amountMinor INTEGER NOT NULL CHECK (typeof(amountMinor) = 'integer' AND amountMinor >= 0),
            PRIMARY KEY (placeId, procedureId)
        )
        """,
        "CREATE INDEX place_rate_procedure ON place_rate (procedureId)",
        """
        CREATE TABLE work_entry (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            localDate TEXT    NOT NULL CHECK (
                typeof(localDate) = 'text'
                AND localDate GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                AND date(localDate) IS localDate
            ),
            placeId   INTEGER NOT NULL REFERENCES place (id)
        )
        """,
        "CREATE UNIQUE INDEX work_entry_date_place ON work_entry (localDate, placeId)",
        "CREATE INDEX work_entry_place ON work_entry (placeId)",
        """
        CREATE TABLE work_line (
            entryId            INTEGER NOT NULL REFERENCES work_entry (id) ON DELETE CASCADE,
            procedureId        INTEGER NOT NULL REFERENCES procedure (id),
            quantity           INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity BETWEEN 1 AND $INT_MAX),
            appliedAmountMinor INTEGER NOT NULL CHECK (typeof(appliedAmountMinor) = 'integer' AND appliedAmountMinor >= 0),
            PRIMARY KEY (entryId, procedureId)
        )
        """,
        "CREATE INDEX work_line_procedure ON work_line (procedureId)",
        """
        CREATE TABLE dataset_settings (
            id           INTEGER PRIMARY KEY CHECK (id = 1),
            currencyCode TEXT    NOT NULL CHECK (typeof(currencyCode) = 'text' AND currencyCode GLOB '[A-Z][A-Z][A-Z]')
        )
        """,
        "INSERT INTO dataset_settings (id, currencyCode) VALUES (1, 'BRL')",
        """
        CREATE TABLE device_state (
            id             INTEGER PRIMARY KEY CHECK (id = 1),
            dataGeneration INTEGER NOT NULL CHECK (typeof(dataGeneration) = 'integer' AND dataGeneration >= 1)
        )
        """,
        "INSERT INTO device_state (id, dataGeneration) VALUES (1, 1)",
    ).map { it.trimIndent() }

    // Backup guard state lives in device_state and is never part of a snapshot or touched by restore.
    // The permission column is added last because its CHECK refers to the earlier columns.
    private val V2: List<String> = listOf(
        """
        ALTER TABLE device_state ADD COLUMN backupAccountId TEXT CHECK (
            backupAccountId IS NULL OR (typeof(backupAccountId) = 'text' AND length(backupAccountId) > 0)
        )
        """,
        """
        ALTER TABLE device_state ADD COLUMN backupSearchState TEXT NOT NULL DEFAULT 'not_searched' CHECK (
            backupSearchState IN ('not_searched', 'searching', 'found_none', 'found_copy', 'failed')
        )
        """,
        """
        ALTER TABLE device_state ADD COLUMN backupChoice TEXT NOT NULL DEFAULT 'none' CHECK (
            backupChoice IN ('none', 'restore', 'replace_confirmed', 'postponed')
        )
        """,
        """
        ALTER TABLE device_state ADD COLUMN backupDriveFileId TEXT CHECK (
            backupDriveFileId IS NULL OR (typeof(backupDriveFileId) = 'text' AND length(backupDriveFileId) > 0)
        )
        """,
        """
        ALTER TABLE device_state ADD COLUMN backupLastSuccessAt TEXT CHECK (
            backupLastSuccessAt IS NULL OR (
                typeof(backupLastSuccessAt) = 'text'
                AND backupLastSuccessAt GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]*Z'
            )
        )
        """,
        """
        ALTER TABLE device_state ADD COLUMN backupBoundGeneration INTEGER CHECK (
            backupBoundGeneration IS NULL
            OR (typeof(backupBoundGeneration) = 'integer' AND backupBoundGeneration >= 1)
        )
        """,
        """
        ALTER TABLE device_state ADD COLUMN backupDailyAllowed INTEGER NOT NULL DEFAULT 0 CHECK (
            backupDailyAllowed IN (0, 1)
            AND (
                backupDailyAllowed = 0
                OR (
                    backupAccountId IS NOT NULL
                    AND backupBoundGeneration IS NOT NULL
                    AND backupSearchState IN ('found_none', 'found_copy')
                )
            )
        )
        """,
    ).map { it.trimIndent() }

    // backupRemoteRevision has no default: NULL means "unknown" for pre-v3 rows, so the first update compares modification times instead.
    private val V3: List<String> = listOf(
        """
        ALTER TABLE device_state ADD COLUMN backupRemoteRevision TEXT CHECK (
            backupRemoteRevision IS NULL OR (
                typeof(backupRemoteRevision) = 'text'
                AND length(backupRemoteRevision) > 0
                AND backupDriveFileId IS NOT NULL
            )
        )
        """,
    ).map { it.trimIndent() }

    // Number of procedures scheduled for the day at the place ("A"); NULL means not entered. Existing entries get NULL.
    private val V4: List<String> = listOf(
        """
        ALTER TABLE work_entry ADD COLUMN scheduledCount INTEGER CHECK (
            scheduledCount IS NULL
            OR (typeof(scheduledCount) = 'integer' AND scheduledCount BETWEEN 0 AND $INT_MAX)
        )
        """,
    ).map { it.trimIndent() }

    // Fingerprint of the data set as last uploaded to or restored from Drive, so a change made within the daily interval is still backed up.
    // NULL (rows from before v5) means unknown, and the next automatic run uploads.
    private val V5: List<String> = listOf(
        """
        ALTER TABLE device_state ADD COLUMN backupDataHash TEXT CHECK (
            backupDataHash IS NULL OR (typeof(backupDataHash) = 'text' AND length(backupDataHash) > 0)
        )
        """,
    ).map { it.trimIndent() }
}

fun SQLiteConnection.readDataGeneration(): Long =
    query("SELECT dataGeneration FROM device_state WHERE id = 1", mapper = { it.getLong(0) })
        .singleOrNull() ?: error("device_state has no data generation row")
