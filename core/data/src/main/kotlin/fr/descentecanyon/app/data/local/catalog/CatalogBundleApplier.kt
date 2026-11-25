package fr.descentecanyon.app.data.local.catalog

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The changes of a catalog update: whole canyons to write again, canyons gone, shared tables. */
class CatalogChanges(
    /** Bundles of scripts/app/catalog_bundles.py: every row of one canyon, in every table. */
    val bundles: List<JsonObject>,
    val removedCanyonIds: List<Int>,
    /** Reading time of each sheet on the server (canyons.lastUpdated), also for unchanged canyons. */
    val readAt: Map<Int, Long>,
    /** Rows of the shared tables replaced as a whole (forum_users), only when they changed. */
    val sharedTables: Map<String, JsonArray> = emptyMap(),
)

/**
 * Writes catalog changes into the open database, in one transaction: Room tells every screen that
 * reads the catalog, so a canyon that became forbidden shows at once. Rows are written column by
 * column of the bundle, only for the tables and columns this schema has (the server builds for the
 * same Room identity hash).
 */
@Singleton
class CatalogBundleApplier @Inject constructor(private val database: CatalogDatabase) {

    suspend fun apply(changes: CatalogChanges) {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            val columns = HashMap<String, Set<String>>()
            fun columnsOf(table: String): Set<String> = columns.getOrPut(table) {
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                }
            }
            changes.removedCanyonIds.forEach { id -> deleteCanyon(db, id) }
            changes.bundles.forEach { bundle ->
                val canyonId = bundle.getValue("canyonId").jsonPrimitive.intOrNull ?: error("Bundle without canyon id")
                val tables = bundle.getValue("tables").jsonObject
                deleteCanyon(db, canyonId)
                // Referenced rows first: the link tables point at them.
                for (table in INSERT_ORDER) {
                    val rows = tables[table]?.jsonArray ?: continue
                    val known = columnsOf(table)
                    val conflict = if (table in SHARED_REFERENCED) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_ABORT
                    rows.forEach { row ->
                        val values = row.jsonObject.toContentValues(known)
                        // The reading time stays out of bundles (it changes at every reading): the index gives it.
                        if (table == "canyons" && !values.containsKey("lastUpdated")) {
                            values.put("lastUpdated", changes.readAt[canyonId] ?: 0L)
                        }
                        db.insert(table, conflict, values)
                    }
                }
            }
            changes.readAt.forEach { (canyonId, readAtMs) ->
                db.execSQL(
                    "UPDATE canyons SET lastUpdated = ? WHERE id = ? AND lastUpdated < ?",
                    arrayOf<Any>(readAtMs, canyonId, readAtMs),
                )
            }
            changes.sharedTables.forEach { (table, rows) ->
                require(table in SHARED_TABLES) { "Unknown shared table $table" }
                val known = columnsOf(table)
                db.delete(table, null, null)
                rows.forEach { row -> db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, row.jsonObject.toContentValues(known)) }
            }
        }
    }

    private fun deleteCanyon(db: SupportSQLiteDatabase, canyonId: Int) {
        PER_CANYON_TABLES.forEach { (table, key) -> db.delete(table, "`$key` = ?", arrayOf(canyonId)) }
    }

    private fun JsonObject.toContentValues(known: Set<String>): ContentValues = ContentValues().also { values ->
        forEach { (column, value) ->
            if (column !in known) return@forEach
            when {
                value is JsonNull -> values.putNull(column)

                value is JsonPrimitive && value.isString -> values.put(column, value.content)

                value is JsonPrimitive -> {
                    val text = value.content
                    when {
                        value.booleanOrNull != null -> values.put(column, if (value.booleanOrNull == true) 1 else 0)
                        text.any { it == '.' || it == 'e' || it == 'E' } -> values.put(column, text.toDouble())
                        else -> values.put(column, text.toLong())
                    }
                }

                else -> values.put(column, value.toString())
            }
        }
    }

    internal companion object {
        /** Same list as scripts/app/catalog_bundles.py: table and column holding the canyon id. */
        val PER_CANYON_TABLES = listOf(
            "canyon_chains" to "fromCanyonId",
            "search_index" to "id",
            "watersheds" to "canyonId",
            "canyon_tracks" to "canyonId",
            "canyon_regulations" to "canyonId",
            "canyon_bibliography" to "canyonId",
            "geo_points" to "canyonId",
            "canyons" to "id",
        )
        val SHARED_REFERENCED = setOf("bibliography_entries", "regulation_texts")
        val SHARED_TABLES = setOf("forum_users")
        val INSERT_ORDER = listOf(
            "canyons",
            "bibliography_entries",
            "regulation_texts",
            "geo_points",
            "canyon_bibliography",
            "canyon_regulations",
            "canyon_tracks",
            "watersheds",
            "search_index",
            "canyon_chains",
        )
    }
}
