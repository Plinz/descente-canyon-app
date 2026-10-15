package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Small user preferences kept on the device. */
interface PreferencesRepository {
    suspend fun getSelectedHomeFeed(): HomeFeedType?

    suspend fun setSelectedHomeFeed(type: HomeFeedType)

    /** The identity used in the last report sent without an account, if any. */
    suspend fun getContributorIdentity(): ContributorIdentity?

    suspend fun setContributorIdentity(identity: ContributorIdentity)

    /** Current theme, readable synchronously so the first frame already uses it. */
    fun getThemeMode(): ThemeMode

    fun observeThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)
}
