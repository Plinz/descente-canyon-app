package fr.descentecanyon.app.testutil

import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.MapPosition
import fr.descentecanyon.app.domain.model.ReportZone
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PreferencesRepository] for view model tests. */
class FakePreferencesRepository(
    var selectedHomeFeed: HomeFeedType? = null,
    var contributorIdentity: ContributorIdentity? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    var latestReportsZone: ReportZone? = null,
    var mapPosition: MapPosition? = null,
) : PreferencesRepository {

    private val themeModeState = MutableStateFlow(themeMode)

    override suspend fun getSelectedHomeFeed(): HomeFeedType? = selectedHomeFeed

    override suspend fun setSelectedHomeFeed(type: HomeFeedType) {
        selectedHomeFeed = type
    }

    override suspend fun getContributorIdentity(): ContributorIdentity? = contributorIdentity

    override suspend fun setContributorIdentity(identity: ContributorIdentity) {
        contributorIdentity = identity
    }

    override suspend fun clearContributorIdentity() {
        contributorIdentity = null
    }

    override suspend fun getLatestReportsZone(): ReportZone? = latestReportsZone

    override suspend fun setLatestReportsZone(zone: ReportZone?) {
        latestReportsZone = zone
    }

    override fun getThemeMode(): ThemeMode = themeModeState.value

    override fun observeThemeMode(): Flow<ThemeMode> = themeModeState

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeModeState.value = mode
    }

    override fun getLastMapPosition(): MapPosition? = mapPosition

    override fun setLastMapPosition(position: MapPosition) {
        mapPosition = position
    }
}
