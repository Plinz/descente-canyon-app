package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.HomeFeedType

/** Small user preferences kept on the device. */
interface PreferencesRepository {
    suspend fun getSelectedHomeFeed(): HomeFeedType?

    suspend fun setSelectedHomeFeed(type: HomeFeedType)
}
