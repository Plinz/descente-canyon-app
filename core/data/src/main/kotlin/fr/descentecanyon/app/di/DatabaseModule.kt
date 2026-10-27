package fr.descentecanyon.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.local.catalog.CatalogDatabase
import fr.descentecanyon.app.data.local.catalog.CatalogInstaller
import fr.descentecanyon.app.data.local.catalog.dao.BibliographyDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonChainDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.catalog.dao.ForumUserDao
import fr.descentecanyon.app.data.local.catalog.dao.GeoPointDao
import fr.descentecanyon.app.data.local.catalog.dao.RegulationDao
import fr.descentecanyon.app.data.local.catalog.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.catalog.dao.WatershedDao
import fr.descentecanyon.app.data.local.user.UserDatabase
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
import fr.descentecanyon.app.data.local.user.dao.StayDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideCatalogDatabase(@ApplicationContext context: Context, installer: CatalogInstaller): CatalogDatabase {
        // Reference data: replaced as a whole by each APK, never migrated.
        installer.discardOutdatedCatalog()
        return Room.databaseBuilder(context, CatalogDatabase::class.java, CatalogDatabase.DATABASE_NAME)
            .createFromAsset(CatalogDatabase.ASSET_PATH)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        installer.markInstalled()
                    }
                },
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideUserDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.DATABASE_NAME)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .build()

    @Provides fun provideCanyonDao(database: CatalogDatabase): CanyonDao = database.canyonDao()

    @Provides fun provideGeoPointDao(database: CatalogDatabase): GeoPointDao = database.geoPointDao()

    @Provides fun provideBibliographyDao(database: CatalogDatabase): BibliographyDao = database.bibliographyDao()

    @Provides fun provideRegulationDao(database: CatalogDatabase): RegulationDao = database.regulationDao()

    @Provides fun provideCanyonTrackDao(database: CatalogDatabase): CanyonTrackDao = database.canyonTrackDao()

    @Provides fun provideWatershedDao(database: CatalogDatabase): WatershedDao = database.watershedDao()

    @Provides fun provideCanyonChainDao(database: CatalogDatabase): CanyonChainDao = database.canyonChainDao()

    @Provides fun provideSearchIndexDao(database: CatalogDatabase): SearchIndexDao = database.searchIndexDao()

    @Provides fun provideForumUserDao(database: CatalogDatabase): ForumUserDao = database.forumUserDao()

    @Provides fun provideFavoriteDao(database: UserDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun providePendingDebitSubmissionDao(database: UserDatabase): PendingDebitSubmissionDao = database.pendingDebitSubmissionDao()

    @Provides fun provideDebitDao(database: UserDatabase): DebitDao = database.debitDao()

    @Provides fun providePhotoDao(database: UserDatabase): PhotoDao = database.photoDao()

    @Provides fun provideDailyWeatherDao(database: UserDatabase): DailyWeatherDao = database.dailyWeatherDao()

    @Provides fun provideKeyValueDao(database: UserDatabase): KeyValueDao = database.keyValueDao()

    @Provides fun provideRecentCanyonDao(database: UserDatabase): RecentCanyonDao = database.recentCanyonDao()

    @Provides fun provideCanyonNoteDao(database: UserDatabase): CanyonNoteDao = database.canyonNoteDao()

    @Provides fun provideLogbookDao(database: UserDatabase): LogbookDao = database.logbookDao()

    @Provides fun providePlannedOutingDao(database: UserDatabase): PlannedOutingDao = database.plannedOutingDao()

    @Provides fun providePlaceDao(database: UserDatabase): PlaceDao = database.placeDao()

    @Provides fun provideDriveTimeDao(database: UserDatabase): DriveTimeDao = database.driveTimeDao()

    @Provides fun provideStayDao(database: UserDatabase): StayDao = database.stayDao()
}
