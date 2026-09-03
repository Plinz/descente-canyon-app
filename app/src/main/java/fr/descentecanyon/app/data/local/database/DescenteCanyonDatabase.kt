package fr.descentecanyon.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.ForumUserDao
import fr.descentecanyon.app.data.local.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonBibliographyEntity
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.entity.CanyonTrackEntity
import fr.descentecanyon.app.data.local.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.local.entity.DebitEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.ForumUserEntity
import fr.descentecanyon.app.data.local.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.entity.PhotoEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import fr.descentecanyon.app.data.local.entity.SearchIndexEntity
import fr.descentecanyon.app.data.local.entity.WatershedEntity

@Database(
    entities = [
        CanyonEntity::class,
        GeoPointEntity::class,
        DebitEntity::class,
        DailyWeatherEntity::class,
        PhotoEntity::class,
        BibliographyEntryEntity::class,
        CanyonBibliographyEntity::class,
        RegulationTextEntity::class,
        CanyonRegulationEntity::class,
        CanyonTrackEntity::class,
        WatershedEntity::class,
        AppMetadataEntity::class,
        PendingDebitSubmissionEntity::class,
        SearchIndexEntity::class,
        ForumUserEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class DescenteCanyonDatabase : RoomDatabase() {
    abstract fun canyonDao(): CanyonDao
    abstract fun canyonTrackDao(): CanyonTrackDao
    abstract fun geoPointDao(): GeoPointDao
    abstract fun debitDao(): DebitDao
    abstract fun dailyWeatherDao(): DailyWeatherDao
    abstract fun photoDao(): PhotoDao
    abstract fun bibliographyDao(): BibliographyDao
    abstract fun regulationDao(): RegulationDao
    abstract fun watershedDao(): WatershedDao
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun pendingDebitSubmissionDao(): PendingDebitSubmissionDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun forumUserDao(): ForumUserDao

    companion object {
        const val DATABASE_NAME = "descente_canyon_db"
    }
}
