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
import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import fr.descentecanyon.app.data.local.user.dao.RecentCanyonDao
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.user.entity.PhotoEntity
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity

/**
 * Data that must survive app updates: favorites, debit reports waiting to be sent and the caches
 * filled at runtime. Every schema change needs a migration covered by `UserDatabaseMigrationTest`.
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
    ],
    version = 2,
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

        /** Migrations of the user database, oldest first. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)
    }
}
