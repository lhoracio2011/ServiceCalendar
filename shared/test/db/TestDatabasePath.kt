package app.servicecalendar.db

/** A new absolute path for a temporary database file; the file does not exist yet. */
expect fun newTestDatabasePath(): String

/** Deletes the database file and its journal files. */
expect fun deleteTestDatabase(path: String)

/**
 * Whether the native SQLite library can be loaded in the test environment.
 * It is `false` only for local Android unit tests that run on the host JVM, because `sqliteJni`
 * ships only inside the AAR for devices. Database tests return early when it is `false`.
 */
expect fun isNativeSqliteAvailable(): Boolean
