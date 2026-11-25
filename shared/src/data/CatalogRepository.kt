package app.servicecalendar.data

import androidx.sqlite.SQLiteConnection
import app.servicecalendar.db.bindTextOrNull
import app.servicecalendar.db.execute
import app.servicecalendar.db.getTextOrNull
import app.servicecalendar.db.query
import app.servicecalendar.db.readDataGeneration
import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.validateCatalogName
import app.servicecalendar.domain.validatePlaceDraft
import app.servicecalendar.model.Place
import app.servicecalendar.model.PlaceRate
import app.servicecalendar.model.Procedure
import app.servicecalendar.money.MinorAmount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class CatalogRepository(private val store: DataStore) {
    val procedures: Flow<List<Procedure>> =
        store.revision.map { store.read { db -> db.readProcedures() } }.distinctUntilChanged()

    val places: Flow<List<PlaceWithRates>> =
        store.revision.map { store.read { db -> db.readPlaces() } }.distinctUntilChanged()

    val snapshots: Flow<CatalogSnapshot> =
        store.revision.map { store.read { db -> db.readSnapshot() } }.distinctUntilChanged()

    suspend fun createProcedure(name: String, openedGeneration: Long? = null): Long {
        val clean = validateCatalogName(name)
        return store.write { db ->
            db.checkGeneration(openedGeneration)
            db.requireRoom("procedure", CatalogLimits.MAX_PROCEDURES, "procedures")
            db.execute("INSERT INTO procedure (name) VALUES (?)") { bindText(1, clean) }
            db.lastInsertId()
        }
    }

    suspend fun renameProcedure(id: Long, name: String, openedGeneration: Long? = null) {
        val clean = validateCatalogName(name)
        store.write { db ->
            db.checkGeneration(openedGeneration)
            if (!db.exists("SELECT 1 FROM procedure WHERE id = ?", id)) throw ValidationError("id", "procedure not found")
            db.execute("UPDATE procedure SET name = ? WHERE id = ?") {
                bindText(1, clean)
                bindLong(2, id)
            }
        }
    }

    suspend fun removeProcedure(id: Long, openedGeneration: Long? = null): RemovalResult = store.write { db ->
        db.checkGeneration(openedGeneration)
        if (db.exists("SELECT 1 FROM work_line WHERE procedureId = ? LIMIT 1", id)) {
            db.execute("UPDATE procedure SET archived = 1 WHERE id = ?") { bindLong(1, id) }
            RemovalResult.Archived
        } else {
            db.execute("DELETE FROM place_rate WHERE procedureId = ?") { bindLong(1, id) }
            db.execute("DELETE FROM procedure WHERE id = ?") { bindLong(1, id) }
            RemovalResult.Deleted
        }
    }

    suspend fun savePlace(draft: PlaceDraft, openedGeneration: Long? = null): Long {
        val valid = validatePlaceDraft(draft)
        return store.write { db ->
            db.checkGeneration(openedGeneration)
            for (procedureId in valid.rates.keys) {
                if (!db.exists("SELECT 1 FROM procedure WHERE id = ? AND archived = 0", procedureId)) {
                    throw ValidationError("rate.$procedureId", "procedure not found")
                }
            }
            val placeId = if (valid.id == null) {
                db.requireRoom("place", CatalogLimits.MAX_PLACES, "places")
                db.execute("INSERT INTO place (name, address, color) VALUES (?, ?, ?)") {
                    bindText(1, valid.name)
                    bindTextOrNull(2, valid.address)
                    bindLong(3, valid.color)
                }
                db.lastInsertId()
            } else {
                if (!db.exists("SELECT 1 FROM place WHERE id = ?", valid.id)) {
                    throw ValidationError("id", "place not found")
                }
                db.execute("UPDATE place SET name = ?, address = ?, color = ? WHERE id = ?") {
                    bindText(1, valid.name)
                    bindTextOrNull(2, valid.address)
                    bindLong(3, valid.color)
                    bindLong(4, valid.id)
                }
                valid.id
            }
            db.execute(
                "DELETE FROM place_rate WHERE placeId = ? " +
                    "AND procedureId IN (SELECT id FROM procedure WHERE archived = 0)",
            ) { bindLong(1, placeId) }
            db.requireRoom("place_rate", CatalogLimits.MAX_PLACE_RATES, "prices", adding = valid.rates.size)
            for ((procedureId, amount) in valid.rates) {
                db.execute("INSERT INTO place_rate (placeId, procedureId, amountMinor) VALUES (?, ?, ?)") {
                    bindLong(1, placeId)
                    bindLong(2, procedureId)
                    bindLong(3, amount.minor)
                }
            }
            placeId
        }
    }

    suspend fun removePlace(id: Long, openedGeneration: Long? = null): RemovalResult = store.write { db ->
        db.checkGeneration(openedGeneration)
        if (db.exists("SELECT 1 FROM work_entry WHERE placeId = ? LIMIT 1", id)) {
            db.execute("UPDATE place SET archived = 1 WHERE id = ?") { bindLong(1, id) }
            RemovalResult.Archived
        } else {
            db.execute("DELETE FROM place_rate WHERE placeId = ?") { bindLong(1, id) }
            db.execute("DELETE FROM place WHERE id = ?") { bindLong(1, id) }
            RemovalResult.Deleted
        }
    }
}

/** Runs inside the write block so a restore cannot slip in between the check and the write (both hold the same Mutex). */
private fun SQLiteConnection.checkGeneration(openedGeneration: Long?) {
    if (openedGeneration != null && readDataGeneration() != openedGeneration) throw StaleGeneration()
}

private fun SQLiteConnection.readSnapshot(): CatalogSnapshot =
    CatalogSnapshot(generation = readDataGeneration(), procedures = readProcedures(), places = readPlaces())

private fun SQLiteConnection.lastInsertId(): Long =
    query("SELECT last_insert_rowid()", mapper = { it.getLong(0) }).single()

private fun SQLiteConnection.exists(sql: String, id: Long): Boolean =
    query(sql, binder = { bindLong(1, id) }, mapper = { it.getLong(0) }).isNotEmpty()

private fun SQLiteConnection.readProcedures(): List<Procedure> =
    query(
        "SELECT id, name FROM procedure WHERE archived = 0 ORDER BY id",
        mapper = { Procedure(id = it.getLong(0), name = it.getText(1)) },
    // Sorted in Kotlin because SQLite's COLLATE NOCASE does not handle non-ASCII letters.
    ).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

private fun SQLiteConnection.readPlaces(): List<PlaceWithRates> {
    val ratesByPlace = query(
        """
        SELECT r.placeId, r.procedureId, r.amountMinor
        FROM place_rate r JOIN procedure p ON p.id = r.procedureId
        WHERE p.archived = 0
        ORDER BY r.placeId, r.procedureId
        """.trimIndent(),
        mapper = { PlaceRate(it.getLong(0), it.getLong(1), MinorAmount(it.getLong(2))) },
    ).groupBy { it.placeId }
    return query(
        "SELECT id, name, address, color FROM place WHERE archived = 0 ORDER BY id",
        mapper = {
            Place(id = it.getLong(0), name = it.getText(1), address = it.getTextOrNull(2), color = it.getLong(3))
        },
    ).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        .map { PlaceWithRates(it, ratesByPlace[it.id].orEmpty()) }
}
