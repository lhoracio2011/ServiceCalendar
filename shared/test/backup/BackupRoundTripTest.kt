package app.servicecalendar.backup

import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.ReportRepository
import app.servicecalendar.data.ScheduledChange
import app.servicecalendar.data.WorkEntryRepository
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/**
 * The whole backup cycle through the real repositories, coordinator and an in-memory Drive:
 * catalog and entries typed on device 1 → snapshot → JSON upload → download on a clean install (device 2) →
 * validation → restore. Device 2 must end up with exactly the same places, procedures, per-place prices,
 * entries, quantities, A and report totals.
 */
class BackupRoundTripTest {
    private val nov1 = LocalDate(2026, 11, 1)
    private val nov2 = LocalDate(2026, 11, 2)
    private val nov3 = LocalDate(2026, 11, 3)
    private val nov30 = LocalDate(2026, 11, 30)

    private class Device(val store: DataStore, drive: FakeDriveServer, clock: () -> Instant) {
        val catalog = CatalogRepository(store)
        val entries = WorkEntryRepository(store)
        val reports = ReportRepository(store)
        val auth = FakeGoogleAuthProvider(account = null)
        val states = BackupStateStore(store)
        val coordinator = BackupCoordinator(auth, drive.client(auth), store, states, RestoreService(store), now = clock)

        suspend fun connect(): BackupOutcome {
            auth.consentResult = ConsentResult.Granted(FakeGoogleAuthProvider.ACCOUNT_A)
            return coordinator.connectAccount()
        }

        suspend fun generation(): Long = store.currentGeneration()

        suspend fun snapshot(): BackupSnapshot = SnapshotCodec.readSnapshot(store, createdAt = "")
    }

    private class Env(val drive: FakeDriveServer, val first: Device, val second: Device) {
        var now: Instant = Instant.parse("2026-11-10T12:00:00Z")
    }

    private fun roundTripTest(block: suspend (Env) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val firstPath = newTestDatabasePath()
        val secondPath = newTestDatabasePath()
        try {
            runBlocking {
                val drive = FakeDriveServer()
                val firstStore = DataStore(firstPath)
                val secondStore = DataStore(secondPath)
                try {
                    lateinit var env: Env
                    val clock = { env.now }
                    env = Env(drive, Device(firstStore, drive, clock), Device(secondStore, drive, clock))
                    withTimeout(60_000) { block(env) }
                } finally {
                    firstStore.close()
                    secondStore.close()
                }
            }
        } finally {
            deleteTestDatabase(firstPath)
            deleteTestDatabase(secondPath)
        }
    }

    private class Catalog(
        val exam: Long,
        val dressing: Long,
        val massage: Long,
        val notreLabs: Long,
        val clinic: Long,
        val oldPlace: Long,
    )

    /**
     * Two places share procedures at different prices; one procedure and one place are archived with history;
     * entries carry A and several lines.
     */
    private suspend fun Device.enterCatalogAndEntries(): Catalog {
        val exam = catalog.createProcedure("Exam")
        val dressing = catalog.createProcedure("Dressing")
        val massage = catalog.createProcedure("Massage")
        val notreLabs = catalog.savePlace(PlaceDraft(null, "NotreLabs", "Rua 1", 0xFF2E7D32L, mapOf(exam to 1250L, dressing to 775L)))
        val clinic = catalog.savePlace(PlaceDraft(null, "Clinic", null, 0xFF1565C0L, mapOf(exam to 2000L, dressing to 900L, massage to 3000L)))
        val oldPlace = catalog.savePlace(PlaceDraft(null, "Old place", null, 0L, mapOf(exam to 500L)))

        entries.save(nov1, notreLabs, mapOf(exam to 3, dressing to 2), generation(), ScheduledChange.Set(8))
        entries.save(nov1, clinic, mapOf(exam to 2, massage to 1), generation(), ScheduledChange.Set(4))
        entries.save(nov2, clinic, mapOf(dressing to 5), generation())
        entries.save(nov2, oldPlace, mapOf(exam to 1), generation())
        entries.setScheduled(nov3, clinic, 6, generation())

        // Massage has history, so removing it archives it (its rate at Clinic stays); Old place is archived too.
        catalog.removeProcedure(massage)
        catalog.removePlace(oldPlace)
        return Catalog(exam, dressing, massage, notreLabs, clinic, oldPlace)
    }

    private suspend fun Device.restoreFromDrive(): BackupOutcome {
        assertEquals(BackupOutcome.Skipped(BackupSkipReason.AWAITING_CHOICE), connect())
        assertEquals(BackupOutcome.Completed, coordinator.prepareRestore())
        return coordinator.restoreCopy()
    }

    @Test
    fun `clean install restores every place with its own prices entries quantities A and totals`() = roundTripTest { env ->
        val ids = env.first.enterCatalogAndEntries()
        assertEquals(BackupOutcome.Uploaded, env.first.connect())

        assertEquals(BackupOutcome.Completed, env.second.restoreFromDrive())

        assertEquals(env.first.snapshot(), env.second.snapshot())
        assertEquals(env.first.catalog.places.first(), env.second.catalog.places.first())
        assertEquals(env.first.catalog.procedures.first(), env.second.catalog.procedures.first())
        assertEquals(env.first.reports.report(nov1, nov30), env.second.reports.report(nov1, nov30))

        val clinic = env.second.catalog.places.first().single { it.place.id == ids.clinic }
        // Archived Massage is hidden from the active catalog; its rate is kept in the database.
        assertEquals(mapOf(ids.exam to 2000L, ids.dressing to 900L), clinic.rates.associate { it.procedureId to it.amountMinor.minor })
        val notre = env.second.catalog.places.first().single { it.place.id == ids.notreLabs }
        assertEquals(mapOf(ids.exam to 1250L, ids.dressing to 775L), notre.rates.associate { it.procedureId to it.amountMinor.minor })
        assertTrue(env.second.snapshot().placeRates.contains(PlaceRateDto(ids.clinic, ids.massage, 3000L)))

        val report = env.second.reports.report(nov1, nov30)
        assertEquals(MinorAmount(3750 + 1550 + 4000 + 3000 + 4500 + 500), report.total)
        assertEquals(18L, report.scheduled)

        val editor = env.second.entries.loadEditor(nov1, ids.clinic)
        assertEquals(MinorAmount(2000), editor.rows.single { it.procedureId == ids.exam }.currentRate)
        assertEquals(2, editor.rows.single { it.procedureId == ids.exam }.savedQuantity)
        assertEquals(4, editor.scheduled)
    }

    @Test
    fun `prices changed and places added after the first backup reach the restore after the next upload`() = roundTripTest { env ->
        val ids = env.first.enterCatalogAndEntries()
        assertEquals(BackupOutcome.Uploaded, env.first.connect())

        // After the first backup: Clinic gets a new Exam price and loses Dressing, NotreLabs gets Massage-free edits, a new place appears.
        env.first.catalog.savePlace(PlaceDraft(ids.clinic, "Clinic", null, 0xFF1565C0L, mapOf(ids.exam to 2200L)))
        val lab = env.first.catalog.savePlace(PlaceDraft(null, "Lab 2", null, 0xFF6A1B9AL, mapOf(ids.exam to 1800L, ids.dressing to 600L)))
        env.first.entries.save(nov3, lab, mapOf(ids.dressing to 2), env.first.generation())
        env.now += 25.hours
        assertEquals(BackupOutcome.Uploaded, env.first.coordinator.runScheduled())

        assertEquals(BackupOutcome.Completed, env.second.restoreFromDrive())

        assertEquals(env.first.snapshot(), env.second.snapshot())
        val places = env.second.catalog.places.first().associateBy { it.place.id }
        assertEquals(mapOf(ids.exam to 2200L), places.getValue(ids.clinic).rates.associate { it.procedureId to it.amountMinor.minor })
        assertEquals(mapOf(ids.exam to 1800L, ids.dressing to 600L), places.getValue(lab).rates.associate { it.procedureId to it.amountMinor.minor })
        // Old lines keep the price applied when they were saved.
        assertEquals(env.first.reports.report(nov1, nov30), env.second.reports.report(nov1, nov30))
    }

    /**
     * The client's report: NotreLabs came back, the other place had no prices. The second place's prices were typed
     * a few hours after the last upload; the app was opened again the same day, so before the fix every automatic run
     * skipped the upload as "updated less than 24 hours ago" and the copy on Drive never got those prices.
     */
    @Test
    fun `prices entered within 24 hours of the last backup are uploaded on the next app open`() = roundTripTest { env ->
        val exam = env.first.catalog.createProcedure("Exam")
        val dressing = env.first.catalog.createProcedure("Dressing")
        val notreLabs = env.first.catalog.savePlace(PlaceDraft(null, "NotreLabs", null, 0xFF2E7D32L, mapOf(exam to 1250L, dressing to 775L)))
        val other = env.first.catalog.savePlace(PlaceDraft(null, "Other", null, 0xFF1565C0L, emptyMap()))
        env.first.entries.save(nov1, notreLabs, mapOf(exam to 3), env.first.generation())
        assertEquals(BackupOutcome.Uploaded, env.first.connect())

        env.now += 3.hours
        env.first.catalog.savePlace(PlaceDraft(other, "Other", null, 0xFF1565C0L, mapOf(exam to 2000L, dressing to 900L)))
        env.first.entries.save(nov2, other, mapOf(exam to 2), env.first.generation())
        env.now += 2.hours
        assertEquals(BackupOutcome.Uploaded, env.first.coordinator.runOnAppOpen())

        assertEquals(BackupOutcome.Completed, env.second.restoreFromDrive())
        val restored = env.second.catalog.places.first().associateBy { it.place.id }
        assertEquals(mapOf(exam to 2000L, dressing to 900L), restored.getValue(other).rates.associate { it.procedureId to it.amountMinor.minor })
        assertEquals(env.first.snapshot(), env.second.snapshot())
        assertEquals(env.first.reports.report(nov1, nov30), env.second.reports.report(nov1, nov30))
    }

    @Test
    fun `restoring the same copy twice creates no duplicates`() = roundTripTest { env ->
        env.first.enterCatalogAndEntries()
        assertEquals(BackupOutcome.Uploaded, env.first.connect())
        assertEquals(BackupOutcome.Completed, env.second.restoreFromDrive())
        val once = env.second.snapshot()
        val reportOnce = env.second.reports.report(nov1, nov30)

        // Second restore of the same copy straight through the restore service (the coordinator offers it only in the choice).
        val copy = SnapshotCodec.decode(checkNotNull(env.drive.content(env.drive.fileIds.single())))
        RestoreService(env.second.store).restore(copy)

        assertEquals(once, env.second.snapshot())
        assertEquals(reportOnce, env.second.reports.report(nov1, nov30))
        assertEquals(once.placeRates.size, once.placeRates.toSet().size)
    }

    @Test
    fun `local edits after restore get new ids that do not collide with restored rows`() = roundTripTest { env ->
        val ids = env.first.enterCatalogAndEntries()
        assertEquals(BackupOutcome.Uploaded, env.first.connect())
        assertEquals(BackupOutcome.Completed, env.second.restoreFromDrive())

        val newProcedure = env.second.catalog.createProcedure("New")
        val newPlace = env.second.catalog.savePlace(PlaceDraft(null, "New place", null, 0L, mapOf(newProcedure to 100L)))

        assertTrue(newProcedure > ids.massage)
        assertTrue(newPlace > ids.oldPlace)
        val restoredClinic = env.second.catalog.places.first().single { it.place.id == ids.clinic }
        assertEquals(2, restoredClinic.rates.size)
        assertNotNull(env.second.catalog.places.first().singleOrNull { it.place.id == newPlace })
    }
}
