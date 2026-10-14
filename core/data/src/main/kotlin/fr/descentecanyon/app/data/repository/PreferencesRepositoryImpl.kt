package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepositoryImpl @Inject constructor(private val snapshotStore: HomeFeedSnapshotStore) : PreferencesRepository {
    override suspend fun getSelectedHomeFeed(): HomeFeedType? = snapshotStore.readSelectedFeedType()

    override suspend fun setSelectedHomeFeed(type: HomeFeedType) = snapshotStore.writeSelectedFeedType(type)
}
