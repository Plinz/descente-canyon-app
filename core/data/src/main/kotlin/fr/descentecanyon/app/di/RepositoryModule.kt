package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.AuthRepositoryImpl
import fr.descentecanyon.app.data.repository.CanyonNotesRepositoryImpl
import fr.descentecanyon.app.data.repository.CanyonRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitPredictionRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitPredictionSupportRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitSubmissionRepositoryImpl
import fr.descentecanyon.app.data.repository.EdfPracticabilityRepositoryImpl
import fr.descentecanyon.app.data.repository.FavoritesRepositoryImpl
import fr.descentecanyon.app.data.repository.ForumRepositoryImpl
import fr.descentecanyon.app.data.repository.ForumUserRepositoryImpl
import fr.descentecanyon.app.data.repository.InterestRatingRepositoryImpl
import fr.descentecanyon.app.data.repository.NotificationCenterRepositoryImpl
import fr.descentecanyon.app.data.repository.OutingRepositoryImpl
import fr.descentecanyon.app.data.repository.PhotoRepositoryImpl
import fr.descentecanyon.app.data.repository.PreferencesRepositoryImpl
import fr.descentecanyon.app.data.repository.RecentCanyonsRepositoryImpl
import fr.descentecanyon.app.data.repository.WeatherRepositoryImpl
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.EdfPracticabilityRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.ForumRepository
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.InterestRatingRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindCanyonRepository(impl: CanyonRepositoryImpl): CanyonRepository

    @Binds
    @Singleton
    abstract fun bindDebitRepository(impl: DebitRepositoryImpl): DebitRepository

    @Binds
    @Singleton
    abstract fun bindDebitPredictionSupportRepository(impl: DebitPredictionSupportRepositoryImpl): DebitPredictionSupportRepository

    @Binds
    @Singleton
    abstract fun bindDebitPredictionRepository(impl: DebitPredictionRepositoryImpl): DebitPredictionRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository

    @Binds
    @Singleton
    abstract fun bindRecentCanyonsRepository(impl: RecentCanyonsRepositoryImpl): RecentCanyonsRepository

    @Binds
    @Singleton
    abstract fun bindCanyonNotesRepository(impl: CanyonNotesRepositoryImpl): CanyonNotesRepository

    @Binds
    @Singleton
    abstract fun bindForumRepository(impl: ForumRepositoryImpl): ForumRepository

    @Binds
    @Singleton
    abstract fun bindForumUserRepository(impl: ForumUserRepositoryImpl): ForumUserRepository

    @Binds
    @Singleton
    abstract fun bindPhotoRepository(impl: PhotoRepositoryImpl): PhotoRepository

    @Binds
    @Singleton
    abstract fun bindNotificationCenterRepository(impl: NotificationCenterRepositoryImpl): NotificationCenterRepository

    @Binds
    @Singleton
    abstract fun bindDebitSubmissionRepository(impl: DebitSubmissionRepositoryImpl): DebitSubmissionRepository

    @Binds
    @Singleton
    abstract fun bindInterestRatingRepository(impl: InterestRatingRepositoryImpl): InterestRatingRepository

    @Binds
    @Singleton
    abstract fun bindEdfPracticabilityRepository(impl: EdfPracticabilityRepositoryImpl): EdfPracticabilityRepository

    @Binds
    @Singleton
    abstract fun bindWeatherRepository(impl: WeatherRepositoryImpl): WeatherRepository

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository

    @Binds
    @Singleton
    abstract fun bindOutingRepository(impl: OutingRepositoryImpl): OutingRepository
}
