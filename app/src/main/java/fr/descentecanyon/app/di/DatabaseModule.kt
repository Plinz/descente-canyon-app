package fr.descentecanyon.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.ForumUserDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_debit_submissions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `canyonId` INTEGER NOT NULL,
                    `observerName` TEXT NOT NULL,
                    `observerEmail` TEXT,
                    `observationDate` TEXT NOT NULL,
                    `isDescended` INTEGER NOT NULL,
                    `debitLevel` TEXT NOT NULL,
                    `waterTemperature` TEXT NOT NULL,
                    `airTemperature` TEXT NOT NULL,
                    `comment` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "communesJson", "TEXT")
            ensureCanyonColumn(db, "bassin", "TEXT")
            ensureCanyonColumn(db, "coursEau", "TEXT")
            ensureCanyonColumn(db, "geologie", "TEXT")
            ensureCanyonColumn(db, "historique", "TEXT")
            ensureCanyonColumn(db, "remarques", "TEXT")
            ensureCanyonColumn(db, "hasSpecificRegulation", "INTEGER NOT NULL DEFAULT 0")
            ensureTableColumn(db, "debits", "isDescended", "INTEGER")
            ensureTableColumn(db, "debits", "waterTemperature", "TEXT")
            ensureTableColumn(db, "debits", "airTemperature", "TEXT")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "communesJson", "TEXT")
            ensureCanyonColumn(db, "bassin", "TEXT")
            ensureCanyonColumn(db, "coursEau", "TEXT")
            ensureCanyonColumn(db, "geologie", "TEXT")
            ensureCanyonColumn(db, "historique", "TEXT")
            ensureCanyonColumn(db, "remarques", "TEXT")
            ensureCanyonColumn(db, "hasSpecificRegulation", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bibliography_entries` (
                    `id` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `resourceType` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `authorsJson` TEXT,
                    `publicationYear` INTEGER,
                    `reference` TEXT,
                    `editor` TEXT,
                    `status` TEXT,
                    `scale` TEXT,
                    `detailUrl` TEXT,
                    `url` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canyon_bibliography` (
                    `canyonId` INTEGER NOT NULL,
                    `bibliographyId` INTEGER NOT NULL,
                    PRIMARY KEY(`canyonId`, `bibliographyId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `regulation_texts` (
                    `id` INTEGER NOT NULL,
                    `status` TEXT,
                    `action` TEXT,
                    `title` TEXT NOT NULL,
                    `summary` TEXT,
                    `remark` TEXT,
                    `details` TEXT,
                    `effectiveDate` TEXT,
                    `textUrl` TEXT,
                    `attachmentsJson` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canyon_regulations` (
                    `canyonId` INTEGER NOT NULL,
                    `regulationId` INTEGER NOT NULL,
                    PRIMARY KEY(`canyonId`, `regulationId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `app_metadata` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "isForbidden", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "isForbidden", "INTEGER NOT NULL DEFAULT 0")
            ensureTableColumn(db, "debits", "isDescended", "INTEGER")
            ensureTableColumn(db, "debits", "waterTemperature", "TEXT")
            ensureTableColumn(db, "debits", "airTemperature", "TEXT")
            recreateDebitsTable(db)

            db.execSQL("DROP TABLE IF EXISTS `canyon_bibliography`")
            db.execSQL("DROP TABLE IF EXISTS `bibliography_entries`")
            db.execSQL("DROP TABLE IF EXISTS `canyon_regulations`")
            db.execSQL("DROP TABLE IF EXISTS `regulation_texts`")
            db.execSQL("DROP TABLE IF EXISTS `app_metadata`")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bibliography_entries` (
                    `id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `resourceType` TEXT,
                    `title` TEXT NOT NULL,
                    `authorsJson` TEXT,
                    `publicationYear` INTEGER,
                    `reference` TEXT,
                    `editor` TEXT,
                    `status` TEXT,
                    `scale` TEXT,
                    `detailUrl` TEXT,
                    `url` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canyon_bibliography` (
                    `canyonId` INTEGER NOT NULL,
                    `bibliographyId` TEXT NOT NULL,
                    PRIMARY KEY(`canyonId`, `bibliographyId`),
                    FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`bibliographyId`) REFERENCES `bibliography_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_bibliography_canyonId` ON `canyon_bibliography` (`canyonId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_bibliography_bibliographyId` ON `canyon_bibliography` (`bibliographyId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `regulation_texts` (
                    `id` INTEGER NOT NULL,
                    `status` TEXT,
                    `action` TEXT,
                    `title` TEXT NOT NULL,
                    `summary` TEXT,
                    `remark` TEXT,
                    `details` TEXT,
                    `effectiveDate` TEXT,
                    `textUrl` TEXT NOT NULL,
                    `attachmentsJson` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canyon_regulations` (
                    `canyonId` INTEGER NOT NULL,
                    `regulationId` INTEGER NOT NULL,
                    PRIMARY KEY(`canyonId`, `regulationId`),
                    FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`regulationId`) REFERENCES `regulation_texts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_regulations_canyonId` ON `canyon_regulations` (`canyonId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_regulations_regulationId` ON `canyon_regulations` (`regulationId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `app_metadata` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `watersheds` (
                    `canyonId` INTEGER NOT NULL,
                    `areaKm2` REAL,
                    `geometryJson` TEXT,
                    `bboxMinLongitude` REAL,
                    `bboxMinLatitude` REAL,
                    `bboxMaxLongitude` REAL,
                    `bboxMaxLatitude` REAL,
                    PRIMARY KEY(`canyonId`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `geo_points` RENAME TO `geo_points_legacy`")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `geo_points` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `canyonId` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `title` TEXT,
                    `remark` TEXT,
                    FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `geo_points` (`id`, `canyonId`, `type`, `latitude`, `longitude`, `title`, `remark`)
                SELECT `id`, `canyonId`, `type`, `latitude`, `longitude`, `label`, NULL
                FROM `geo_points_legacy`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `geo_points_legacy`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_geo_points_canyonId` ON `geo_points` (`canyonId`)")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `daily_weather` (
                    `canyonId` INTEGER NOT NULL,
                    `date` TEXT NOT NULL,
                    `timezone` TEXT NOT NULL,
                    `targetLatitude` REAL NOT NULL,
                    `targetLongitude` REAL NOT NULL,
                    `targetSource` TEXT NOT NULL,
                    `sourceKind` TEXT NOT NULL,
                    `precipitationSum` REAL,
                    `rainSum` REAL,
                    `snowfallSum` REAL,
                    `temperature2mMean` REAL,
                    `temperature2mMin` REAL,
                    `temperature2mMax` REAL,
                    `precipitationHours` REAL,
                    `fetchedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`canyonId`, `date`),
                    FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_weather_canyonId` ON `daily_weather` (`canyonId`)")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "sourceType", "TEXT NOT NULL DEFAULT 'DESCENTE_CANYON'")
            ensureCanyonColumn(db, "sourceKey", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `canyons` SET `sourceType` = 'DESCENTE_CANYON' WHERE `sourceType` IS NULL OR TRIM(`sourceType`) = ''")
            db.execSQL("UPDATE `canyons` SET `sourceKey` = 'dc:' || `id` WHERE `sourceKey` IS NULL OR TRIM(`sourceKey`) = ''")
            recreateCanyonTracksTable(db)
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            recreateCanyonTracksTable(db)
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureTableColumn(db, "pending_debit_submissions", "personalComment", "TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createSearchIndexTable(db)
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createForumUsersTable(db)
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureTableColumn(db, "daily_weather", "weatherCode", "INTEGER")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DescenteCanyonDatabase {
        val builder = Room.databaseBuilder(
            context,
            DescenteCanyonDatabase::class.java,
            DescenteCanyonDatabase.DATABASE_NAME,
        )
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_3)
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
            .addMigrations(MIGRATION_7_8)
            .addMigrations(MIGRATION_8_9)
            .addMigrations(MIGRATION_9_10)
            .addMigrations(MIGRATION_10_11)
            .addMigrations(MIGRATION_11_12)
            .addMigrations(MIGRATION_12_13)
            .addMigrations(MIGRATION_13_14)
            .addMigrations(MIGRATION_14_15)
            .createFromAsset(PREPACKAGED_DATABASE_ASSET_PATH)

        return builder.build()
    }

    @Provides
    fun provideCanyonDao(database: DescenteCanyonDatabase): CanyonDao = database.canyonDao()

    @Provides
    fun provideCanyonTrackDao(database: DescenteCanyonDatabase): CanyonTrackDao = database.canyonTrackDao()

    @Provides
    fun provideGeoPointDao(database: DescenteCanyonDatabase): GeoPointDao = database.geoPointDao()

    @Provides
    fun provideDebitDao(database: DescenteCanyonDatabase): DebitDao = database.debitDao()

    @Provides
    fun provideDailyWeatherDao(database: DescenteCanyonDatabase): DailyWeatherDao = database.dailyWeatherDao()

    @Provides
    fun providePhotoDao(database: DescenteCanyonDatabase): PhotoDao = database.photoDao()

    @Provides
    fun provideBibliographyDao(database: DescenteCanyonDatabase): BibliographyDao = database.bibliographyDao()

    @Provides
    fun provideRegulationDao(database: DescenteCanyonDatabase): RegulationDao = database.regulationDao()

    @Provides
    fun provideWatershedDao(database: DescenteCanyonDatabase): WatershedDao = database.watershedDao()

    @Provides
    fun provideAppMetadataDao(database: DescenteCanyonDatabase): AppMetadataDao = database.appMetadataDao()

    @Provides
    fun providePendingDebitSubmissionDao(database: DescenteCanyonDatabase): PendingDebitSubmissionDao {
        return database.pendingDebitSubmissionDao()
    }

    @Provides
    fun provideSearchIndexDao(database: DescenteCanyonDatabase): SearchIndexDao = database.searchIndexDao()

    @Provides
    fun provideForumUserDao(database: DescenteCanyonDatabase): ForumUserDao = database.forumUserDao()

    private fun ensureCanyonColumn(
        db: SupportSQLiteDatabase,
        columnName: String,
        sqlType: String,
    ) {
        ensureTableColumn(db, "canyons", columnName, sqlType)
    }

    private fun ensureTableColumn(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        sqlType: String,
    ) {
        if (!db.tableHasColumn(tableName, columnName)) {
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $sqlType")
        }
    }

    private fun createSearchIndexTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_index` (
                `id` INTEGER NOT NULL,
                `nom` TEXT NOT NULL,
                `nomComplet` TEXT NOT NULL,
                `pays` TEXT NOT NULL,
                `countryTokensJson` TEXT,
                `region` TEXT,
                `departement` TEXT,
                `departmentTokensJson` TEXT,
                `subdivisionsByCountryJson` TEXT,
                `commune` TEXT,
                `massif` TEXT,
                `bassin` TEXT,
                `coursEau` TEXT,
                `cotation` TEXT NOT NULL,
                `cotationVertical` INTEGER,
                `cotationAquatic` INTEGER,
                `cotationEngagement` INTEGER,
                `interet` REAL,
                `nbVotes` INTEGER NOT NULL,
                `altitudeDepart` INTEGER,
                `denivele` INTEGER,
                `longueur` INTEGER,
                `cascadeMax` INTEGER,
                `cordeMin` INTEGER,
                `hasSpecificRegulation` INTEGER NOT NULL,
                `isForbidden` INTEGER NOT NULL,
                `hasNavette` INTEGER NOT NULL,
                `isFavorite` INTEGER NOT NULL,
                `representativeLat` REAL,
                `representativeLng` REAL,
                `url` TEXT NOT NULL,
                `searchableText` TEXT NOT NULL,
                `normalizedNom` TEXT NOT NULL,
                `normalizedNomComplet` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }

    private fun createForumUsersTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `forum_users` (
                `username` TEXT NOT NULL,
                `normalizedUsername` TEXT NOT NULL,
                `forumUserId` INTEGER,
                `profileUrl` TEXT,
                `source` TEXT NOT NULL,
                `hasForumActivity` INTEGER NOT NULL,
                `hasDebitActivity` INTEGER NOT NULL,
                `forumPostCount` INTEGER NOT NULL,
                `debitObservationCount` INTEGER NOT NULL,
                `lastForumPostAt` TEXT,
                `lastForumPostUrl` TEXT,
                `lastDebitObservationAt` TEXT,
                `lastDebitObservationUrl` TEXT,
                `updatedAt` TEXT NOT NULL,
                PRIMARY KEY(`username`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_forum_users_normalizedUsername` ON `forum_users` (`normalizedUsername`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_forum_users_forumUserId` ON `forum_users` (`forumUserId`)")
    }

    private fun recreateDebitsTable(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debits` RENAME TO `debits_legacy`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `debits` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `canyonId` INTEGER NOT NULL,
                `date` TEXT NOT NULL,
                `niveau` TEXT NOT NULL,
                `auteur` TEXT,
                `isDescended` INTEGER,
                `waterTemperature` TEXT,
                `airTemperature` TEXT,
                `commentaire` TEXT,
                FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `debits` (`id`, `canyonId`, `date`, `niveau`, `auteur`, `isDescended`, `waterTemperature`, `airTemperature`, `commentaire`)
            SELECT `id`, `canyonId`, `date`, `niveau`, `auteur`, `isDescended`, `waterTemperature`, `airTemperature`, `commentaire`
            FROM `debits_legacy`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `debits_legacy`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debits_canyonId` ON `debits` (`canyonId`)")
    }

    private fun recreateCanyonTracksTable(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `canyon_tracks`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `canyon_tracks` (
                `canyonId` INTEGER NOT NULL,
                `trackId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `role` TEXT,
                `isPrimary` INTEGER NOT NULL,
                `sourceFile` TEXT,
                `pointCount` INTEGER,
                `geometryJson` TEXT,
                `bboxMinLongitude` REAL,
                `bboxMinLatitude` REAL,
                `bboxMaxLongitude` REAL,
                `bboxMaxLatitude` REAL,
                PRIMARY KEY(`canyonId`, `trackId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_tracks_canyonId` ON `canyon_tracks` (`canyonId`)")
    }

    private fun SupportSQLiteDatabase.tableHasColumn(
        tableName: String,
        columnName: String,
    ): Boolean {
        val cursor: Cursor = query("PRAGMA table_info(`$tableName`)")
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (nameIndex >= 0 && it.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    private const val PREPACKAGED_DATABASE_ASSET_PATH = "databases/descente_canyon_prepackaged.db"
}
