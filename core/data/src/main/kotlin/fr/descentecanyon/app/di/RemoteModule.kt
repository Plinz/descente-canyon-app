package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSource
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSourceImpl
import fr.descentecanyon.app.data.remote.scraper.InterestRatingRemoteSource
import fr.descentecanyon.app.data.remote.scraper.InterestRatingRemoteSourceImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteModule {

    @Binds
    @Singleton
    abstract fun bindDebitSubmissionRemoteSource(impl: DebitSubmissionRemoteSourceImpl): DebitSubmissionRemoteSource

    @Binds
    @Singleton
    abstract fun bindInterestRatingRemoteSource(impl: InterestRatingRemoteSourceImpl): InterestRatingRemoteSource
}
