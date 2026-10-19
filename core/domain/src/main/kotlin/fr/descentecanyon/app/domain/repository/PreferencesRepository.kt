package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.MapPosition
import fr.descentecanyon.app.domain.model.ReportZone
import fr.descentecanyon.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Small user preferences kept on the device. */
interface PreferencesRepository {
    suspend fun getSelectedHomeFeed(): HomeFeedType?

    suspend fun setSelectedHomeFeed(type: HomeFeedType)

    /** The identity used in the last report sent without an account, if any. */
    suspend fun getContributorIdentity(): ContributorIdentity?

    suspend fun setContributorIdentity(identity: ContributorIdentity)

    /** Forgets the name and e-mail of reports sent without an account. */
    suspend fun clearContributorIdentity()

    /** Area of the latest reports feed, kept between launches (null: every country). */
    suspend fun getLatestReportsZone(): ReportZone?

    suspend fun setLatestReportsZone(zone: ReportZone?)

    /** Current theme, readable synchronously so the first frame already uses it. */
    fun getThemeMode(): ThemeMode

    fun observeThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /** Last position of the map, readable synchronously so the map opens there at once. */
    fun getLastMapPosition(): MapPosition?

    fun setLastMapPosition(position: MapPosition)
}
