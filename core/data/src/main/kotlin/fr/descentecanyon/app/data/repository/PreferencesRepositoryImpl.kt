package fr.descentecanyon.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.MapPosition
import fr.descentecanyon.app.domain.model.ReportZone
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Singleton
class PreferencesRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val snapshotStore: HomeFeedSnapshotStore,
    private val keyValueDao: KeyValueDao,
) : PreferencesRepository {

    // The theme is needed before the first frame: SharedPreferences can be read synchronously.
    private val settings: SharedPreferences by lazy { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }

    override suspend fun getSelectedHomeFeed(): HomeFeedType? = snapshotStore.readSelectedFeedType()

    override suspend fun setSelectedHomeFeed(type: HomeFeedType) = snapshotStore.writeSelectedFeedType(type)

    override suspend fun getContributorIdentity(): ContributorIdentity? {
        val name = keyValueDao.get(KEY_CONTRIBUTOR_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val email = keyValueDao.get(KEY_CONTRIBUTOR_EMAIL)?.takeIf { it.isNotBlank() }
        return ContributorIdentity(name = name, email = email)
    }

    override suspend fun setContributorIdentity(identity: ContributorIdentity) {
        keyValueDao.putAll(
            listOf(
                KeyValueEntity(KEY_CONTRIBUTOR_NAME, identity.name.trim()),
                KeyValueEntity(KEY_CONTRIBUTOR_EMAIL, identity.email?.trim().orEmpty()),
            ),
        )
    }

    override suspend fun clearContributorIdentity() {
        keyValueDao.putAll(listOf(KeyValueEntity(KEY_CONTRIBUTOR_NAME, ""), KeyValueEntity(KEY_CONTRIBUTOR_EMAIL, "")))
    }

    override suspend fun getLatestReportsZone(): ReportZone? {
        val country = keyValueDao.get(KEY_REPORTS_COUNTRY)?.takeIf { it.isNotBlank() } ?: return null
        return ReportZone(country = country, department = keyValueDao.get(KEY_REPORTS_DEPARTMENT)?.takeIf { it.isNotBlank() })
    }

    override suspend fun setLatestReportsZone(zone: ReportZone?) {
        keyValueDao.putAll(
            listOf(
                KeyValueEntity(KEY_REPORTS_COUNTRY, zone?.country.orEmpty()),
                KeyValueEntity(KEY_REPORTS_DEPARTMENT, zone?.department.orEmpty()),
            ),
        )
    }

    override fun getThemeMode(): ThemeMode = settings.getString(KEY_THEME_MODE, null)
        ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
        ?: ThemeMode.SYSTEM

    override fun observeThemeMode(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_THEME_MODE) trySend(getThemeMode())
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        trySend(getThemeMode())
        awaitClose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        settings.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    override fun getLastMapPosition(): MapPosition? {
        val latitude = settings.getString(KEY_MAP_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = settings.getString(KEY_MAP_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val zoom = settings.getString(KEY_MAP_ZOOM, null)?.toDoubleOrNull() ?: return null
        return MapPosition(latitude, longitude, zoom)
    }

    override fun setLastMapPosition(position: MapPosition) {
        settings.edit {
            putString(KEY_MAP_LATITUDE, position.latitude.toString())
            putString(KEY_MAP_LONGITUDE, position.longitude.toString())
            putString(KEY_MAP_ZOOM, position.zoom.toString())
        }
    }

    private companion object {
        const val SETTINGS_PREFS = "app_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_MAP_LATITUDE = "map.latitude"
        const val KEY_MAP_LONGITUDE = "map.longitude"
        const val KEY_MAP_ZOOM = "map.zoom"
        const val KEY_CONTRIBUTOR_NAME = "contributor.name"
        const val KEY_CONTRIBUTOR_EMAIL = "contributor.email"
        const val KEY_REPORTS_COUNTRY = "home.reports.country"
        const val KEY_REPORTS_DEPARTMENT = "home.reports.department"
    }
}
