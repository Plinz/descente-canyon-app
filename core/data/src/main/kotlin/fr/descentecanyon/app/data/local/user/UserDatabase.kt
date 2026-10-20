package fr.descentecanyon.app.data.local.user

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.descentecanyon.app.data.local.user.dao.CanyonNoteDao
import fr.descentecanyon.app.data.local.user.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.user.dao.DebitDao
import fr.descentecanyon.app.data.local.user.dao.DriveTimeDao
import fr.descentecanyon.app.data.local.user.dao.FavoriteDao
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.dao.LogbookDao
import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import fr.descentecanyon.app.data.local.user.dao.PlaceDao
import fr.descentecanyon.app.data.local.user.dao.PlannedOutingDao
import fr.descentecanyon.app.data.local.user.dao.RecentCanyonDao
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.data.local.user.entity.DriveTimeEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.local.user.entity.LogbookEntryEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.user.entity.PhotoEntity
import fr.descentecanyon.app.data.local.user.entity.PlaceEntity
import fr.descentecanyon.app.data.local.user.entity.PlannedOutingEntity
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data that must survive app updates: favorites, logbook, memos, planned outings, starting places,
 * debit reports waiting to be sent and the caches filled at runtime. Every schema change needs a
 * migration covered by `UserDatabaseMigrationTest`.
 */
@Database(
    entities = [
        FavoriteEntity::class,
        PendingDebitSubmissionEntity::class,
        DebitEntity::class,
        PhotoEntity::class,
        DailyWeatherEntity::class,
        KeyValueEntity::class,
        RecentCanyonEntity::class,
        CanyonNoteEntity::class,
        LogbookEntryEntity::class,
        PlannedOutingEntity::class,
        PlaceEntity::class,
        DriveTimeEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun pendingDebitSubmissionDao(): PendingDebitSubmissionDao
    abstract fun debitDao(): DebitDao
    abstract fun photoDao(): PhotoDao
    abstract fun dailyWeatherDao(): DailyWeatherDao
    abstract fun keyValueDao(): KeyValueDao
    abstract fun recentCanyonDao(): RecentCanyonDao
    abstract fun canyonNoteDao(): CanyonNoteDao
    abstract fun logbookDao(): LogbookDao
    abstract fun plannedOutingDao(): PlannedOutingDao
    abstract fun placeDao(): PlaceDao
    abstract fun driveTimeDao(): DriveTimeDao

    companion object {
        const val DATABASE_NAME = "user.db"

        /** Version 2: recently viewed canyons and personal notes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_canyons` " +
                        "(`canyonId` INTEGER NOT NULL, `viewedAt` INTEGER NOT NULL, PRIMARY KEY(`canyonId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `canyon_notes` " +
                        "(`canyonId` INTEGER NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`canyonId`))",
                )
            }
        }

        /** Version 3: logbook of descended canyons. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logbook_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canyonId` INTEGER NOT NULL, `canyonName` TEXT NOT NULL, `date` TEXT NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_logbook_entries_canyonId_date` ON `logbook_entries` (`canyonId`, `date`)",
                )
            }
        }

        /** Version 4: reports read back before being sent again, and reports refused by the site kept for the user. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pending_debit_submissions` ADD COLUMN `failedAttempts` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pending_debit_submissions` ADD COLUMN `rejectedAt` INTEGER")
                db.execSQL("ALTER TABLE `pending_debit_submissions` ADD COLUMN `rejectionReason` TEXT")
            }
        }

        /** Version 5: logbook outings gain the flow seen, companions, duration, origin and site report. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `logbook_entries` ADD COLUMN `debitLevel` TEXT")
                db.execSQL("ALTER TABLE `logbook_entries` ADD COLUMN `companions` TEXT")
                db.execSQL("ALTER TABLE `logbook_entries` ADD COLUMN `durationMinutes` INTEGER")
                db.execSQL("ALTER TABLE `logbook_entries` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE `logbook_entries` ADD COLUMN `siteObservationId` INTEGER")
                db.execSQL("ALTER TABLE `logbook_entries` ADD COLUMN `updatedAt` INTEGER")
            }
        }

        /**
         * Version 6: several planned outings with the user's choices, starting places and their drive
         * times. The single outing of version 5, kept as a value, becomes the first planned outing.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `planned_outings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canyonId` INTEGER NOT NULL, `canyonName` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                        "`expectedBack` INTEGER NOT NULL, `latestBack` INTEGER NOT NULL, `alertAt` INTEGER NOT NULL, " +
                        "`departure` INTEGER, `groupSize` INTEGER, `relativeInformed` INTEGER NOT NULL, `choicesJson` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_outings_start` ON `planned_outings` (`start`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `stayStart` TEXT, `stayEnd` TEXT, " +
                        "`lastUsedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drive_times` (`placeId` INTEGER NOT NULL, `canyonId` INTEGER NOT NULL, " +
                        "`toLatitude` REAL NOT NULL, `toLongitude` REAL NOT NULL, `minutes` INTEGER NOT NULL, `distanceKm` REAL, " +
                        "`source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`placeId`, `canyonId`))",
                )
                val legacy = db.query("SELECT value, updatedAt FROM key_values WHERE `key` = ?", arrayOf(LEGACY_OUTING_KEY)).use { cursor ->
                    if (cursor.moveToFirst()) LegacyOuting.decode(cursor.getString(0))?.let { it to cursor.getLong(1) } else null
                }
                legacy?.let { (outing, updatedAt) ->
                    db.insert(
                        "planned_outings",
                        SQLiteDatabase.CONFLICT_REPLACE,
                        ContentValues().apply {
                            put("canyonId", outing.canyonId)
                            put("canyonName", outing.canyonName)
                            put("start", outing.startEpochMs)
                            // The return of version 5 was the latest one.
                            put("expectedBack", outing.expectedBackEpochMs)
                            put("latestBack", outing.expectedBackEpochMs)
                            put("alertAt", outing.alertAtEpochMs)
                            put("relativeInformed", 0)
                            put("updatedAt", updatedAt)
                        },
                    )
                }
                db.execSQL("DELETE FROM key_values WHERE `key` = '$LEGACY_OUTING_KEY'")
            }
        }

        /** Migrations of the user database, oldest first. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        /** Key of the single outing of version 5. */
        private const val LEGACY_OUTING_KEY = "outing.current"
    }
}

/** The single outing of user database version 5, as it was kept in `key_values`. */
@Serializable
private data class LegacyOuting(
    val canyonId: Int,
    val canyonName: String,
    val startEpochMs: Long,
    val expectedBackEpochMs: Long,
    val alertAtEpochMs: Long,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(payload: String?): LegacyOuting? = payload?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(serializer(), it) }.getOrNull()
        }
    }
}
