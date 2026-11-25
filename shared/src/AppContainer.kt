package app.servicecalendar

import app.servicecalendar.backup.BackupCoordinator
import app.servicecalendar.backup.BackupStateStore
import app.servicecalendar.backup.DriveBackupClient
import app.servicecalendar.backup.GoogleAuthProvider
import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.ReportRepository
import app.servicecalendar.data.WorkEntryRepository
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class AppContainer private constructor(
    val dataStore: DataStore,
) {
    val catalogRepository: CatalogRepository = CatalogRepository(dataStore)

    val workEntryRepository: WorkEntryRepository = WorkEntryRepository(dataStore)

    val reportRepository: ReportRepository = ReportRepository(dataStore)

    /** UI code must restore through [backupCoordinator], not directly; the coordinator guards against races with uploads. */
    val restoreService: app.servicecalendar.backup.RestoreService = app.servicecalendar.backup.RestoreService(dataStore)

    val backupStateStore: BackupStateStore = BackupStateStore(dataStore)

    @OptIn(ExperimentalAtomicApi::class)
    private val authProvider = AtomicReference<GoogleAuthProvider?>(null)

    /**
     * Call at process start, before the first use of the Drive client or coordinator, in both the UI and background tasks.
     * Repeating the same provider is a no-op; a different one is rejected because state may already be bound to the first account.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun setGoogleAuthProvider(provider: GoogleAuthProvider) {
        if (authProvider.compareAndSet(null, provider)) return
        check(authProvider.load() === provider) { "GoogleAuthProvider is already set for this process" }
    }

    @OptIn(ExperimentalAtomicApi::class)
    val hasGoogleAuthProvider: Boolean get() = authProvider.load() != null

    @OptIn(ExperimentalAtomicApi::class)
    val driveBackupClient: DriveBackupClient by lazy {
        val provider = authProvider.load()
            ?: error("GoogleAuthProvider is not set: call AppContainer.setGoogleAuthProvider at process start")
        DriveBackupClient.withPlatformEngine(provider)
    }

    @OptIn(ExperimentalAtomicApi::class)
    val backupCoordinator: BackupCoordinator by lazy {
        val drive = driveBackupClient
        BackupCoordinator(
            auth = checkNotNull(authProvider.load()),
            drive = drive,
            store = dataStore,
            states = backupStateStore,
            restoreService = restoreService,
        )
    }

    @OptIn(ExperimentalAtomicApi::class)
    companion object {
        const val DEFAULT_DATABASE_NAME = "service-calendar.db"

        private val current = AtomicReference<AppContainer?>(null)

        fun initialize(databaseName: String = DEFAULT_DATABASE_NAME): AppContainer {
            current.load()?.let { return it }
            val created = AppContainer(DataStore(databaseName))
            return if (current.compareAndSet(null, created)) created else checkNotNull(current.load())
        }

        val instance: AppContainer
            get() = current.load()
                ?: error("AppContainer is not initialized: call AppContainer.initialize at process start")

        internal fun resetForTests() {
            current.store(null)
        }
    }
}
