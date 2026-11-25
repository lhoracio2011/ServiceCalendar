package app.servicecalendar.backup

import androidx.sqlite.SQLiteConnection
import app.servicecalendar.data.DataStore
import app.servicecalendar.db.getLongOrNull
import app.servicecalendar.db.getTextOrNull
import app.servicecalendar.db.query
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SnapshotCodec {
    // kotlinx coerces "1250" to a number and 12 to text; the contract forbids both, so literal shapes are checked separately.
    private val format = Json {
        // Unknown keys are tolerated for forward compatibility; missing required fields and wrong types are errors.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        isLenient = false
        coerceInputValues = false
    }

    /** Reads the whole data set in one DataStore.read (a single transaction), so the snapshot is consistent. device_state is never read. */
    suspend fun readSnapshot(store: DataStore, createdAt: String): BackupSnapshot =
        store.read { db -> readSnapshot(db, createdAt) }

    /** The same read inside a block the caller already holds (DataStore read, write or restore). */
    internal fun readSnapshot(db: SQLiteConnection, createdAt: String): BackupSnapshot =
        BackupSnapshot(
            schemaVersion = BackupSnapshot.CURRENT_VERSION,
            createdAt = createdAt,
            currencyCode = db.readCurrency(),
            procedures = db.readProcedures(),
            places = db.readPlaces(),
            placeRates = db.readPlaceRates(),
            workEntries = db.readWorkEntries(),
            workLines = db.readWorkLines(),
        )

    fun encode(snapshot: BackupSnapshot): String = format.encodeToString(BackupSnapshot.serializer(), snapshot)

    /**
     * Identifies the data of a snapshot read with [readSnapshot]: the creation time and format version are not data.
     * 64-bit FNV-1a over the encoded JSON; used only to tell whether the local data changed since the last backup.
     */
    fun dataFingerprint(snapshot: BackupSnapshot): String {
        val bytes = encode(snapshot.copy(schemaVersion = BackupSnapshot.CURRENT_VERSION, createdAt = "")).encodeToByteArray()
        var hash = FNV_OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        return "fnv1a64:" + hash.toULong().toString(16).padStart(16, '0') + ":" + bytes.size
    }

    private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME: Long = 0x100000001b3L

    fun decode(json: String): BackupSnapshot =
        decodeElement(
            try {
                parseElement(json)
            } catch (e: SerializationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw SerializationException(e.message ?: "Invalid JSON", e)
            },
        )

    internal fun parseElement(text: String): JsonElement = format.parseToJsonElement(text)

    internal fun decodeElement(element: JsonElement): BackupSnapshot {
        checkLiteralTypes(element)
        try {
            return format.decodeFromJsonElement(BackupSnapshot.serializer(), element)
        } catch (e: SerializationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message ?: "Invalid JSON", e)
        }
    }
}

private fun SQLiteConnection.readCurrency(): String =
    query("SELECT currencyCode FROM dataset_settings WHERE id = 1", mapper = { it.getText(0) })
        .singleOrNull() ?: error("dataset_settings has no currency row")

private fun SQLiteConnection.readProcedures(): List<ProcedureDto> =
    query("SELECT id, name, archived FROM procedure ORDER BY id", mapper = {
        ProcedureDto(id = it.getLong(0), name = it.getText(1), archived = it.getLong(2) != 0L)
    })

private fun SQLiteConnection.readPlaces(): List<PlaceDto> =
    query("SELECT id, name, address, color, archived FROM place ORDER BY id", mapper = {
        PlaceDto(
            id = it.getLong(0),
            name = it.getText(1),
            address = it.getTextOrNull(2),
            color = it.getLong(3),
            archived = it.getLong(4) != 0L,
        )
    })

private fun SQLiteConnection.readPlaceRates(): List<PlaceRateDto> =
    query("SELECT placeId, procedureId, amountMinor FROM place_rate ORDER BY placeId, procedureId", mapper = {
        PlaceRateDto(placeId = it.getLong(0), procedureId = it.getLong(1), amountMinor = it.getLong(2))
    })

private fun SQLiteConnection.readWorkEntries(): List<WorkEntryDto> =
    query("SELECT id, localDate, placeId, scheduledCount FROM work_entry ORDER BY id", mapper = {
        WorkEntryDto(
            id = it.getLong(0),
            localDate = it.getText(1),
            placeId = it.getLong(2),
            scheduledCount = it.getLongOrNull(3),
        )
    })

private fun SQLiteConnection.readWorkLines(): List<WorkLineDto> =
    query(
        "SELECT entryId, procedureId, quantity, appliedAmountMinor FROM work_line ORDER BY entryId, procedureId",
        mapper = {
            WorkLineDto(
                entryId = it.getLong(0),
                procedureId = it.getLong(1),
                quantity = it.getLong(2),
                appliedAmountMinor = it.getLong(3),
            )
        },
    )

private val INTEGER_LITERAL = Regex("-?[0-9]+")

private enum class Literal { INTEGER, NULLABLE_INTEGER, TEXT, NULLABLE_TEXT, BOOLEAN }

private val ROOT_FIELDS = mapOf(
    "schemaVersion" to Literal.INTEGER,
    "createdAt" to Literal.TEXT,
    "currencyCode" to Literal.TEXT,
)

private val ROW_FIELDS: Map<String, Map<String, Literal>> = mapOf(
    "procedures" to mapOf("id" to Literal.INTEGER, "name" to Literal.TEXT, "archived" to Literal.BOOLEAN),
    "places" to mapOf(
        "id" to Literal.INTEGER, "name" to Literal.TEXT, "address" to Literal.NULLABLE_TEXT,
        "color" to Literal.INTEGER, "archived" to Literal.BOOLEAN,
    ),
    "placeRates" to mapOf(
        "placeId" to Literal.INTEGER, "procedureId" to Literal.INTEGER, "amountMinor" to Literal.INTEGER,
    ),
    "workEntries" to mapOf(
        "id" to Literal.INTEGER, "localDate" to Literal.TEXT, "placeId" to Literal.INTEGER,
        "scheduledCount" to Literal.NULLABLE_INTEGER,
    ),
    "workLines" to mapOf(
        "entryId" to Literal.INTEGER, "procedureId" to Literal.INTEGER,
        "quantity" to Literal.INTEGER, "appliedAmountMinor" to Literal.INTEGER,
    ),
)

private fun checkLiteralTypes(root: JsonElement) {
    if (root !is JsonObject) return
    checkFields("root", root, ROOT_FIELDS)
    for ((table, fields) in ROW_FIELDS) {
        val rows = root[table] as? JsonArray ?: continue
        rows.forEachIndexed { index, row ->
            if (row is JsonObject) checkFields("$table[$index]", row, fields)
        }
    }
}

private fun checkFields(where: String, obj: JsonObject, fields: Map<String, Literal>) {
    for ((name, kind) in fields) {
        val value = obj[name] ?: continue
        val ok = when (kind) {
            Literal.NULLABLE_TEXT -> value is JsonNull || (value is JsonPrimitive && value.isString)
            Literal.TEXT -> value is JsonPrimitive && value !is JsonNull && value.isString
            Literal.INTEGER -> value.isIntegerLiteral()
            Literal.NULLABLE_INTEGER -> value is JsonNull || value.isIntegerLiteral()
            Literal.BOOLEAN -> value is JsonPrimitive && !value.isString && (value.content == "true" || value.content == "false")
        }
        if (!ok) throw SerializationException("Field $where.$name has the wrong type")
    }
}

private fun JsonElement.isIntegerLiteral(): Boolean =
    this is JsonPrimitive && this !is JsonNull && !isString && INTEGER_LITERAL.matches(content)
