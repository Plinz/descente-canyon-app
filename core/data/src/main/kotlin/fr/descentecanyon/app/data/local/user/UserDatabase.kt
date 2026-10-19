package fr.descentecanyon.app.data.local.user

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.descentecanyon.app.data.local.user.dao.CanyonNoteDao
import fr.descentecanyon.app.data.local.user.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.user.dao.DebitDao
import fr.descentecanyon.app.data.local.user.dao.FavoriteDao
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.dao.LogbookDao
import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import fr.descentecanyon.app.data.local.user.dao.RecentCanyonDao
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.local.user.entity.LogbookEntryEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.user.entity.PhotoEntity
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity

/**
 * Data that must survive app updates: favorites, logbook, memos, debit reports waiting to be sent
 * and the caches filled at runtime. Every schema change needs a migration covered by `UserDatabaseMigrationTest`.
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
    ],
    version = 5,
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

        /** Migrations of the user database, oldest first. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
