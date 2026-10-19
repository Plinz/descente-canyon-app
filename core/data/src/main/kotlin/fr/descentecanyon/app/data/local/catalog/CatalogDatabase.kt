package fr.descentecanyon.app.data.local.catalog

import androidx.room.Database
import androidx.room.RoomDatabase
import fr.descentecanyon.app.data.local.catalog.dao.BibliographyDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.catalog.dao.ForumUserDao
import fr.descentecanyon.app.data.local.catalog.dao.GeoPointDao
import fr.descentecanyon.app.data.local.catalog.dao.RegulationDao
import fr.descentecanyon.app.data.local.catalog.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.catalog.dao.WatershedDao
import fr.descentecanyon.app.data.local.catalog.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.catalog.entity.CanyonBibliographyEntity
import fr.descentecanyon.app.data.local.catalog.entity.CanyonEntity
import fr.descentecanyon.app.data.local.catalog.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.catalog.entity.CanyonTrackEntity
import fr.descentecanyon.app.data.local.catalog.entity.ForumUserEntity
import fr.descentecanyon.app.data.local.catalog.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.catalog.entity.RegulationTextEntity
import fr.descentecanyon.app.data.local.catalog.entity.SearchIndexEntity
import fr.descentecanyon.app.data.local.catalog.entity.WatershedEntity

/**
 * The canyon catalog embedded in the APK (generated at build time by
 * `scripts/app/generate_prepackaged_room_db.py` from `offline-data/full/room-import`).
 *
 * It is reference data only: [CatalogInstaller] replaces the whole file when the app is updated, so
 * this database never needs migrations. User data lives in [fr.descentecanyon.app.data.local.user.UserDatabase].
 */
@Database(
    entities = [
        CanyonEntity::class,
        GeoPointEntity::class,
        BibliographyEntryEntity::class,
        CanyonBibliographyEntity::class,
        RegulationTextEntity::class,
        CanyonRegulationEntity::class,
        CanyonTrackEntity::class,
        WatershedEntity::class,
        SearchIndexEntity::class,
        ForumUserEntity::class,
    ],
    version = CatalogDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun canyonDao(): CanyonDao
    abstract fun geoPointDao(): GeoPointDao
    abstract fun bibliographyDao(): BibliographyDao
    abstract fun regulationDao(): RegulationDao
    abstract fun canyonTrackDao(): CanyonTrackDao
    abstract fun watershedDao(): WatershedDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun forumUserDao(): ForumUserDao

    companion object {
        const val SCHEMA_VERSION = 2
        const val DATABASE_NAME = "catalog.db"
        const val ASSET_PATH = "databases/catalog.db"
    }
}
