package fr.descentecanyon.app.data.local

import fr.descentecanyon.app.data.local.catalog.CatalogDatabase
import fr.descentecanyon.app.data.local.catalog.CatalogInstaller
import fr.descentecanyon.app.data.local.legacy.LegacyDatabaseMigrator
import fr.descentecanyon.app.data.local.legacy.LegacyMigrationReport
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Prepares the local databases at startup: catalog copied from the APK, data of older versions migrated. */
@Singleton
class LocalDataInitializer @Inject constructor(
    private val catalogInstaller: CatalogInstaller,
    private val catalogDatabase: CatalogDatabase,
    private val legacyDatabaseMigrator: LegacyDatabaseMigrator,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Whether [initialize] has real work to do (first launch, app update, data of version <= 2.2). */
    suspend fun needsInitialization(): Boolean = withContext(ioDispatcher) {
        catalogInstaller.needsInstall() || legacyDatabaseMigrator.hasLegacyDatabase()
    }

    /** Opens the catalog (copying it from the APK when needed) then migrates the legacy database. */
    suspend fun initialize(): LegacyMigrationReport? = withContext(ioDispatcher) {
        catalogDatabase.openHelper.writableDatabase
        legacyDatabaseMigrator.migrate()
    }
}
