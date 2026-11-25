package app.servicecalendar.backup

import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.RemovalResult
import app.servicecalendar.data.WorkEntryRepository
import app.servicecalendar.db.deleteTestDatabase
import app.servicecalendar.db.execute
import app.servicecalendar.db.isNativeSqliteAvailable
import app.servicecalendar.db.newTestDatabasePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Snapshot codec: encoding, decoding and the JSON format, plus reading a snapshot from the database. */
class SnapshotCodecTest {
    private val integerText = Regex("-?[0-9]+")
    private val dateText = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val utcText = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")

    private fun rootOf(snapshot: BackupSnapshot): JsonObject =
        Json.parseToJsonElement(SnapshotCodec.encode(snapshot)).jsonObject

    @Test
    fun `round trip of the sample snapshot loses nothing`() {
        val snapshot = sampleSnapshot()
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `round trip keeps archived rows`() {
        val snapshot = snapshotWithArchivedRows()
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertTrue(decoded.procedures.any { it.archived })
        assertTrue(decoded.places.any { it.archived })
    }

    @Test
    fun `round trip of an empty dataset`() {
        val empty = BackupSnapshot(
            schemaVersion = BackupSnapshot.CURRENT_VERSION,
            createdAt = TEST_CREATED_AT,
            currencyCode = "BRL",
            procedures = emptyList(),
            places = emptyList(),
            placeRates = emptyList(),
            workEntries = emptyList(),
            workLines = emptyList(),
        )
        assertEquals(empty, SnapshotCodec.decode(SnapshotCodec.encode(empty)))
    }

    @Test
    fun `current schema version is 2 and is written to the json`() {
        assertEquals(2, BackupSnapshot.CURRENT_VERSION)
        assertEquals(2, rootOf(sampleSnapshot())["schemaVersion"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `round trip keeps the scheduled count and an entry with only a scheduled count`() {
        val snapshot = snapshotWithScheduledOnlyEntry()
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(8L, decoded.workEntries.single { it.id == 1L }.scheduledCount)
        assertEquals(5L, decoded.workEntries.single { it.id == 5L }.scheduledCount)
        assertNull(decoded.workEntries.single { it.id == 2L }.scheduledCount)
    }

    @Test
    fun `scheduled count is a json integer or an explicit null`() {
        val entries = rootOf(sampleSnapshot())["workEntries"]!!.jsonArray.map { it.jsonObject }
        assertIntegerLiteral(entries[0]["scheduledCount"])
        assertEquals("8", entries[0]["scheduledCount"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, entries[1]["scheduledCount"])
    }

    @Test
    fun `a version 1 entry without scheduled count decodes with null`() {
        val root = rootOf(sampleSnapshot())
        val v1 = JsonObject(
            root + ("schemaVersion" to JsonPrimitive(1)) + (
                "workEntries" to JsonArray(
                    root["workEntries"]!!.jsonArray.map { e -> JsonObject(e.jsonObject.filterKeys { it != "scheduledCount" }) },
                )
                ),
        )
        val decoded = SnapshotCodec.decode(v1.toString())
        assertEquals(1, decoded.schemaVersion)
        assertTrue(decoded.workEntries.all { it.scheduledCount == null })
        assertEquals(sampleSnapshot().workLines, decoded.workLines)
    }

    @Test
    fun `a fractional textual or boolean scheduled count fails to decode`() {
        val text = SnapshotCodec.encode(sampleSnapshot())
        for (bad in listOf("8.5", "\"8\"", "true", "8e0")) {
            assertFailsWith<SerializationException>(bad) {
                SnapshotCodec.decode(text.replaceFirst("\"scheduledCount\":8", "\"scheduledCount\":$bad"))
            }
        }
    }

    @Test
    fun `createdAt is a UTC timestamp ending with Z and is written as is`() {
        val root = rootOf(sampleSnapshot(createdAt = "2026-11-09T21:30:00Z"))
        val createdAt = root["createdAt"]!!.jsonPrimitive.content
        assertEquals("2026-11-09T21:30:00Z", createdAt)
        assertTrue(utcText.matches(createdAt))
    }

    @Test
    fun `utcTimestamp formats an instant in UTC with whole seconds`() {
        // The fractional part is dropped.
        val instant = Instant.fromEpochSeconds(1_794_259_800, 123_000_000)
        val text = utcTimestamp(instant)
        assertTrue(utcText.matches(text), text)
        assertEquals(text, utcTimestamp(Instant.fromEpochSeconds(instant.epochSeconds)))
    }

    @Test
    fun `amounts are written as json integers`() {
        val root = rootOf(sampleSnapshot())
        for (row in root["placeRates"]!!.jsonArray) {
            assertIntegerLiteral(row.jsonObject["amountMinor"])
        }
        for (row in root["workLines"]!!.jsonArray) {
            assertIntegerLiteral(row.jsonObject["appliedAmountMinor"])
            assertIntegerLiteral(row.jsonObject["quantity"])
        }
        // 12.50 is written as 1250, not 12.5 and not "1250".
        assertEquals("1250", root["placeRates"]!!.jsonArray[0].jsonObject["amountMinor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ids references and colors are json integers`() {
        val root = rootOf(sampleSnapshot())
        for (row in root["procedures"]!!.jsonArray) assertIntegerLiteral(row.jsonObject["id"])
        for (row in root["places"]!!.jsonArray) {
            assertIntegerLiteral(row.jsonObject["id"])
            assertIntegerLiteral(row.jsonObject["color"])
        }
        for (row in root["workEntries"]!!.jsonArray) {
            assertIntegerLiteral(row.jsonObject["id"])
            assertIntegerLiteral(row.jsonObject["placeId"])
        }
        assertEquals(TEST_COLOR_A.toString(), root["places"]!!.jsonArray[0].jsonObject["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `entry dates are YYYY-MM-DD strings`() {
        val dates = rootOf(sampleSnapshot())["workEntries"]!!.jsonArray
            .map { it.jsonObject["localDate"]!!.jsonPrimitive }
        assertEquals(4, dates.size)
        for (date in dates) {
            assertTrue(date.isString)
            assertTrue(dateText.matches(date.content), date.content)
            LocalDate.parse(date.content)
        }
    }

    @Test
    fun `archived is a json boolean`() {
        val root = rootOf(snapshotWithArchivedRows())
        for (row in root["procedures"]!!.jsonArray + root["places"]!!.jsonArray) {
            val archived = row.jsonObject["archived"]!!.jsonPrimitive
            assertFalse(archived.isString)
            assertTrue(archived.content == "true" || archived.content == "false")
        }
    }

    @Test
    fun `root object has exactly the documented keys`() {
        val root = rootOf(snapshotWithArchivedRows())
        assertEquals(
            setOf(
                "schemaVersion", "createdAt", "currencyCode",
                "procedures", "places", "placeRates", "workEntries", "workLines",
            ),
            root.keys,
        )
    }

    @Test
    fun `row objects have exactly the documented keys`() {
        val root = rootOf(snapshotWithArchivedRows())
        fun keysOf(table: String): Set<Set<String>> =
            root[table]!!.jsonArray.map { it.jsonObject.keys }.toSet()
        assertEquals(setOf(setOf("id", "name", "archived")), keysOf("procedures"))
        assertEquals(setOf(setOf("id", "name", "address", "color", "archived")), keysOf("places"))
        assertEquals(setOf(setOf("placeId", "procedureId", "amountMinor")), keysOf("placeRates"))
        assertEquals(setOf(setOf("id", "localDate", "placeId", "scheduledCount")), keysOf("workEntries"))
        assertEquals(
            setOf(setOf("entryId", "procedureId", "quantity", "appliedAmountMinor")),
            keysOf("workLines"),
        )
    }

    @Test
    fun `json contains no device state account or token fields`() {
        val text = SnapshotCodec.encode(snapshotWithArchivedRows()).lowercase()
        val forbidden = listOf(
            "device_state", "devicestate", "datageneration", "generation",
            "token", "account", "email", "authorization", "refresh", "fileid",
            "lastbackup", "guard", "restoremode",
        )
        for (word in forbidden) assertFalse(word in text, "The snapshot must not contain \"$word\"")
    }

    @Test
    fun `null address is written explicitly and a missing address is read as null`() {
        val root = rootOf(sampleSnapshot())
        assertEquals(JsonNull, root["places"]!!.jsonArray[1].jsonObject["address"])

        val withoutAddress = JsonObject(
            root.toMutableMap().apply {
                this["places"] = JsonArray(
                    root["places"]!!.jsonArray.map { place ->
                        JsonObject(place.jsonObject.filterKeys { it != "address" })
                    },
                )
            },
        )
        val decoded = SnapshotCodec.decode(withoutAddress.toString())
        assertNull(decoded.places[0].address)
        assertNull(decoded.places[1].address)
    }

    @Test
    fun `unknown keys are ignored`() {
        val root = rootOf(sampleSnapshot())
        val withExtra = JsonObject(root + ("futureField" to JsonPrimitive("x")))
        assertEquals(sampleSnapshot(), SnapshotCodec.decode(withExtra.toString()))
    }

    @Test
    fun `a missing root field fails to decode`() {
        val root = rootOf(sampleSnapshot())
        for (key in root.keys) {
            val broken = JsonObject(root - key)
            assertFailsWith<SerializationException>("Decoding must fail without \"$key\"") {
                SnapshotCodec.decode(broken.toString())
            }
        }
    }

    @Test
    fun `a missing row field fails to decode`() {
        val root = rootOf(sampleSnapshot())
        for (table in listOf("procedures", "places", "placeRates", "workEntries", "workLines")) {
            val first = root[table]!!.jsonArray[0].jsonObject
            for (key in first.keys) {
                if (table == "places" && key == "address") continue // the address is optional
                if (table == "workEntries" && key == "scheduledCount") continue // absent in version 1
                val brokenRow = JsonObject(first - key)
                val broken = JsonObject(
                    root + (table to JsonArray(listOf(brokenRow) + root[table]!!.jsonArray.drop(1))),
                )
                assertFailsWith<SerializationException>("Decoding must fail without \"$table.$key\"") {
                    SnapshotCodec.decode(broken.toString())
                }
            }
        }
    }

    @Test
    fun `a fractional or textual amount fails to decode`() {
        val text = SnapshotCodec.encode(sampleSnapshot())
        assertFailsWith<SerializationException> {
            SnapshotCodec.decode(text.replaceFirst("\"amountMinor\":1250", "\"amountMinor\":12.5"))
        }
        assertFailsWith<SerializationException> {
            SnapshotCodec.decode(text.replaceFirst("\"amountMinor\":1250", "\"amountMinor\":\"1250\""))
        }
    }

    @Test
    fun `malformed json fails to decode`() {
        assertFailsWith<SerializationException> { SnapshotCodec.decode("{ not json") }
        assertFailsWith<SerializationException> { SnapshotCodec.decode("") }
    }

    private fun assertIntegerLiteral(element: kotlinx.serialization.json.JsonElement?) {
        val primitive = element!!.jsonPrimitive
        assertFalse(primitive.isString, "A number must not be a string: $primitive")
        assertTrue(integerText.matches(primitive.content), "Expected an integer: ${primitive.content}")
    }

    private fun dbTest(block: suspend CoroutineScope.(store: DataStore) -> Unit) {
        if (!isNativeSqliteAvailable()) return
        val path = newTestDatabasePath()
        try {
            runBlocking {
                val store = DataStore(path)
                try {
                    withTimeout(60_000) { block(store) }
                } finally {
                    store.close()
                }
            }
        } finally {
            deleteTestDatabase(path)
        }
    }

    /** Builds the extended set in the database through the repositories. */
    private suspend fun seedExtendedSet(store: DataStore) {
        val catalog = CatalogRepository(store)
        val entries = WorkEntryRepository(store)
        val checkup = catalog.createProcedure("Огляд")
        val dressing = catalog.createProcedure("Перев'язка")
        val a = catalog.savePlace(
            PlaceDraft(null, "A", "вул. Квіткова, 1", TEST_COLOR_A, mapOf(checkup to 1250L, dressing to 775L)),
        )
        val b = catalog.savePlace(PlaceDraft(null, "B", null, TEST_COLOR_B, mapOf(checkup to 2000L)))
        suspend fun save(date: String, place: Long, quantities: Map<Long, Int>) {
            entries.save(LocalDate.parse(date), place, quantities, store.currentGeneration())
        }
        save("2026-11-01", a, mapOf(checkup to 3, dressing to 2))
        entries.setScheduled(LocalDate.parse("2026-11-01"), a, 8, store.currentGeneration())
        save("2026-11-01", b, mapOf(checkup to 2))
        save("2026-11-02", a, mapOf(checkup to 1))
        save("2026-11-03", b, mapOf(checkup to 4))
    }

    @Test
    fun `readSnapshot reads the sample dataset from the database`() = dbTest { store ->
        seedExtendedSet(store)
        val snapshot = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)
        assertEquals(sampleSnapshot(), snapshot)
        assertEquals(SnapshotValidationResult.Valid(snapshot), validateSnapshot(snapshot))
    }

    @Test
    fun `readSnapshot includes an entry with only a scheduled count`() = dbTest { store ->
        seedExtendedSet(store)
        WorkEntryRepository(store).setScheduled(LocalDate(2026, 11, 4), 1, 5, store.currentGeneration())
        val snapshot = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)
        assertEquals(snapshotWithScheduledOnlyEntry(), snapshot)
        assertTrue(validateSnapshot(snapshot) is SnapshotValidationResult.Valid)
    }

    @Test
    fun `readSnapshot includes archived rows and their rates`() = dbTest { store ->
        seedExtendedSet(store)
        val catalog = CatalogRepository(store)
        // The procedure and the place have history, so removing them archives them.
        assertEquals(RemovalResult.Archived, catalog.removeProcedure(1))
        assertEquals(RemovalResult.Archived, catalog.removePlace(1))
        val snapshot = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)
        assertEquals(true, snapshot.procedures.single { it.id == 1L }.archived)
        assertEquals(false, snapshot.procedures.single { it.id == 2L }.archived)
        assertEquals(true, snapshot.places.single { it.id == 1L }.archived)
        assertEquals(5, snapshot.workLines.size)
        assertEquals(3, snapshot.placeRates.size)
        assertTrue(validateSnapshot(snapshot) is SnapshotValidationResult.Valid)
    }

    @Test
    fun `readSnapshot of a fresh database has default currency and no rows`() = dbTest { store ->
        val snapshot = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)
        assertEquals("BRL", snapshot.currencyCode)
        assertEquals(TEST_CREATED_AT, snapshot.createdAt)
        assertEquals(BackupSnapshot.CURRENT_VERSION, snapshot.schemaVersion)
        assertTrue(snapshot.procedures.isEmpty() && snapshot.places.isEmpty())
        assertTrue(snapshot.placeRates.isEmpty() && snapshot.workEntries.isEmpty() && snapshot.workLines.isEmpty())
    }

    @Test
    fun `readSnapshot takes the currency from dataset settings`() = dbTest { store ->
        store.write { db -> db.execute("UPDATE dataset_settings SET currencyCode = 'EUR' WHERE id = 1") }
        assertEquals("EUR", SnapshotCodec.readSnapshot(store, TEST_CREATED_AT).currencyCode)
    }

    @Test
    fun `snapshot leaks no account id drive file id token or email from device state`() = dbTest { store ->
        // The backup and account state is filled in, but it must not reach the snapshot.
        val accountId = "google-sub-1234567890"
        val driveFileId = "drive-file-secret-42"
        val accessToken = "ya29.test-secret-access-token"
        val email = "luiz.secret@example.com"
        seedExtendedSet(store)
        val states = BackupStateStore(store)
        val filled = BackupState(
            accountId = accountId,
            searchState = BackupSearchState.FOUND_COPY,
            choice = BackupChoice.REPLACE_CONFIRMED,
            driveFileId = driveFileId,
            lastSuccessfulBackupAt = Instant.parse("2026-11-10T08:30:15Z"),
            boundGeneration = store.currentGeneration(),
        )
        states.save(filled)
        assertEquals(filled, states.load())
        val account = GoogleAccount(accountId, email)
        val token = AccessTokenResult.Token(accessToken)

        val snapshot = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)
        assertEquals(sampleSnapshot(), snapshot)
        val json = SnapshotCodec.encode(snapshot)
        for (secret in listOf(accountId, driveFileId, accessToken, email, account.displayLabel, token.accessToken)) {
            assertFalse(secret in json, "The snapshot must not contain \"$secret\"")
        }
        val lower = json.lowercase()
        for (word in listOf("backup", "device_state", "account", "token", "email", "drivefile", "2026-11-10")) {
            assertFalse(word in lower, "The snapshot must not contain \"$word\"")
        }
        // toString masks the values so they do not appear in logs.
        for (text in listOf(filled.toString(), account.toString(), token.toString())) {
            for (secret in listOf(accountId, driveFileId, accessToken, email)) {
                assertFalse(secret in text, "toString reveals \"$secret\": $text")
            }
        }
    }

    @Test
    fun `readSnapshot does not change data or revision and ignores generation`() = dbTest { store ->
        seedExtendedSet(store)
        val revision = store.revision.value
        val generation = store.currentGeneration()
        val first = SnapshotCodec.readSnapshot(store, TEST_CREATED_AT)
        assertEquals(revision, store.revision.value)
        assertEquals(generation, store.currentGeneration())
        assertEquals(first, SnapshotCodec.readSnapshot(store, TEST_CREATED_AT))
        assertFalse("dataGeneration" in SnapshotCodec.encode(first))
    }
}
