package fr.descentecanyon.app.data.local.legacy

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.user.entity.PhotoEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What was carried over from the single database of app versions <= 2.2. */
data class LegacyMigrationReport(
    val favorites: Int,
    val pendingSubmissions: Int,
    val debits: Int,
    val photos: Int,
    val dailyWeather: Int,
    val keyValues: Int,
)

/**
 * Moves the user data of the former single database (`descente_canyon_db`, Room versions 1 to 14,
 * where favorites were columns of the catalog) into [UserDatabase], then deletes it.
 *
 * Columns are read only when they exist in the installed version, and rows are inserted with
 * "ignore" semantics, so an interrupted migration can simply run again.
 */
@Singleton
class LegacyDatabaseMigrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userDatabase: UserDatabase,
) {

    fun hasLegacyDatabase(): Boolean = legacyFile().exists()

    suspend fun migrate(): LegacyMigrationReport? {
        val file = legacyFile()
        if (!file.exists()) return null

        val snapshot = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(::readSnapshot)
        userDatabase.withTransaction {
            userDatabase.favoriteDao().insertAll(snapshot.favorites)
            userDatabase.pendingDebitSubmissionDao().insertAllIfAbsent(snapshot.pendingSubmissions)
            userDatabase.debitDao().insertAll(snapshot.debits)
            userDatabase.photoDao().insertAll(snapshot.photos)
            userDatabase.dailyWeatherDao().insertAll(snapshot.dailyWeather)
            userDatabase.keyValueDao().putAllIfAbsent(snapshot.keyValues)
        }
        SQLiteDatabase.deleteDatabase(file)

        return LegacyMigrationReport(
            favorites = snapshot.favorites.size,
            pendingSubmissions = snapshot.pendingSubmissions.size,
            debits = snapshot.debits.size,
            photos = snapshot.photos.size,
            dailyWeather = snapshot.dailyWeather.size,
            keyValues = snapshot.keyValues.size,
        )
    }

    private fun legacyFile(): File = context.getDatabasePath(LEGACY_DATABASE_NAME)

    private fun readSnapshot(db: SQLiteDatabase): LegacySnapshot {
        val tables = db.tableColumns()
        return LegacySnapshot(
            favorites = if ("isFavorite" in tables["canyons"].orEmpty()) {
                db.rows("SELECT id FROM canyons WHERE isFavorite = 1") { FavoriteEntity(canyonId = int("id")!!) }
            } else {
                emptyList()
            },
            pendingSubmissions = db.rowsOf(tables, "pending_debit_submissions") {
                PendingDebitSubmissionEntity(
                    id = long("id") ?: 0,
                    canyonId = int("canyonId") ?: return@rowsOf null,
                    observerName = string("observerName").orEmpty(),
                    observerEmail = string("observerEmail"),
                    observationDate = string("observationDate") ?: return@rowsOf null,
                    isDescended = (int("isDescended") ?: 1) != 0,
                    debitLevel = string("debitLevel").orEmpty(),
                    waterTemperature = string("waterTemperature").orEmpty(),
                    airTemperature = string("airTemperature").orEmpty(),
                    comment = string("comment").orEmpty(),
                    personalComment = string("personalComment").orEmpty(),
                    createdAt = long("createdAt") ?: System.currentTimeMillis(),
                )
            },
            debits = db.rowsOf(tables, "debits") {
                DebitEntity(
                    id = long("id") ?: 0,
                    canyonId = int("canyonId") ?: return@rowsOf null,
                    date = string("date") ?: return@rowsOf null,
                    niveau = string("niveau") ?: return@rowsOf null,
                    auteur = string("auteur"),
                    isDescended = int("isDescended")?.let { it != 0 },
                    waterTemperature = string("waterTemperature"),
                    airTemperature = string("airTemperature"),
                    commentaire = string("commentaire"),
                )
            },
            photos = db.rowsOf(tables, "photos") {
                PhotoEntity(
                    id = long("id") ?: 0,
                    canyonId = int("canyonId") ?: return@rowsOf null,
                    url = string("url") ?: return@rowsOf null,
                    thumbnailUrl = string("thumbnailUrl"),
                    auteur = string("auteur"),
                    description = string("description"),
                    localPath = string("localPath"),
                )
            },
            dailyWeather = db.rowsOf(tables, "daily_weather") {
                DailyWeatherEntity(
                    canyonId = int("canyonId") ?: return@rowsOf null,
                    date = string("date") ?: return@rowsOf null,
                    timezone = string("timezone") ?: "UTC",
                    targetLatitude = double("targetLatitude") ?: return@rowsOf null,
                    targetLongitude = double("targetLongitude") ?: return@rowsOf null,
                    targetSource = string("targetSource").orEmpty(),
                    sourceKind = string("sourceKind").orEmpty(),
                    precipitationSum = double("precipitationSum"),
                    rainSum = double("rainSum"),
                    snowfallSum = double("snowfallSum"),
                    temperature2mMean = double("temperature2mMean"),
                    temperature2mMin = double("temperature2mMin"),
                    temperature2mMax = double("temperature2mMax"),
                    precipitationHours = double("precipitationHours"),
                    fetchedAtEpochMs = long("fetchedAtEpochMs") ?: 0,
                )
            },
            keyValues = db.rowsOf(tables, "app_metadata") {
                val key = string("key")?.takeIf { it in CARRIED_OVER_KEYS } ?: return@rowsOf null
                KeyValueEntity(key = key, value = string("value") ?: return@rowsOf null)
            },
        )
    }

    private class LegacySnapshot(
        val favorites: List<FavoriteEntity>,
        val pendingSubmissions: List<PendingDebitSubmissionEntity>,
        val debits: List<DebitEntity>,
        val photos: List<PhotoEntity>,
        val dailyWeather: List<DailyWeatherEntity>,
        val keyValues: List<KeyValueEntity>,
    )

    companion object {
        const val LEGACY_DATABASE_NAME = "descente_canyon_db"

        /** Home feed snapshots kept by HomeFeedSnapshotStore; the dataset version keys are obsolete. */
        private val CARRIED_OVER_KEYS = setOf(
            "home.latest_debits.snapshot",
            "home.active_topics.snapshot",
            "home.forum_categories.snapshot",
            "home.selected_feed_type",
        )
    }
}

/** Columns of every table of the database. */
private fun SQLiteDatabase.tableColumns(): Map<String, Set<String>> {
    val tables = rows("SELECT name FROM sqlite_master WHERE type = 'table'") { string("name") }.filterNotNull()
    return tables.associateWith { table -> rows("PRAGMA table_info(`$table`)") { string("name") }.filterNotNull().toSet() }
}

private fun <T> SQLiteDatabase.rowsOf(tables: Map<String, Set<String>>, table: String, map: Cursor.() -> T?): List<T> {
    if (table !in tables) return emptyList()
    return rows("SELECT * FROM `$table`", map).filterNotNull()
}

private fun <T> SQLiteDatabase.rows(sql: String, map: Cursor.() -> T): List<T> = rawQuery(sql, null).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.map()) }
}

private fun Cursor.index(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }

private fun Cursor.string(column: String): String? = index(column)?.let(::getString)

private fun Cursor.int(column: String): Int? = index(column)?.let(::getInt)

private fun Cursor.long(column: String): Long? = index(column)?.let(::getLong)

private fun Cursor.double(column: String): Double? = index(column)?.let(::getDouble)
