package fr.descentecanyon.app.data.local.user

import androidx.room.Database
import androidx.room.RoomDatabase
import fr.descentecanyon.app.data.local.user.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.user.dao.DebitDao
import fr.descentecanyon.app.data.local.user.dao.FavoriteDao
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.user.entity.PhotoEntity

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
    ],
    version = 1,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun pendingDebitSubmissionDao(): PendingDebitSubmissionDao
    abstract fun debitDao(): DebitDao
    abstract fun photoDao(): PhotoDao
    abstract fun dailyWeatherDao(): DailyWeatherDao
    abstract fun keyValueDao(): KeyValueDao

    companion object {
        const val DATABASE_NAME = "user.db"

        /** Migrations of the user database, oldest first. */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()
    }
}
