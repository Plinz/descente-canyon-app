package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.AppServerRepositoryImpl
import fr.descentecanyon.app.data.repository.CanyonProposalRepositoryImpl
import fr.descentecanyon.app.data.repository.CommunityTrackRepositoryImpl
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonProposalRepository
import fr.descentecanyon.app.domain.repository.CommunityTrackRepository
import javax.inject.Singleton

/** The app's own server (signed manifest, catalog updates, shared tracks, proposed canyons); BackendConfig comes from the app module. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendModule {

    @Binds
    @Singleton
    abstract fun bindAppServerRepository(impl: AppServerRepositoryImpl): AppServerRepository

    @Binds
    @Singleton
    abstract fun bindCommunityTrackRepository(impl: CommunityTrackRepositoryImpl): CommunityTrackRepository

    @Binds
    @Singleton
    abstract fun bindCanyonProposalRepository(impl: CanyonProposalRepositoryImpl): CanyonProposalRepository
}
