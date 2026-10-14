package fr.descentecanyon.app.e2e.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.descentecanyon.app.data.repository.DebitPredictionSupportRepositoryImpl
import fr.descentecanyon.app.data.repository.ForumUserRepositoryImpl
import fr.descentecanyon.app.data.repository.NotificationCenterRepositoryImpl
import fr.descentecanyon.app.data.repository.PreferencesRepositoryImpl
import fr.descentecanyon.app.di.RepositoryModule
import fr.descentecanyon.app.domain.repository.AuthRepository
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
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.e2e.fakes.FakeAuthRepository
import fr.descentecanyon.app.e2e.fakes.FakeCanyonRepository
import fr.descentecanyon.app.e2e.fakes.FakeDebitPredictionRepository
import fr.descentecanyon.app.e2e.fakes.FakeDebitRepository
import fr.descentecanyon.app.e2e.fakes.FakeDebitSubmissionRepository
import fr.descentecanyon.app.e2e.fakes.FakeEdfPracticabilityRepository
import fr.descentecanyon.app.e2e.fakes.FakeFavoritesRepository
import fr.descentecanyon.app.e2e.fakes.FakeForumRepository
import fr.descentecanyon.app.e2e.fakes.FakeInterestRatingRepository
import fr.descentecanyon.app.e2e.fakes.FakePhotoRepository
import fr.descentecanyon.app.e2e.fakes.FakeWeatherRepository
import javax.inject.Singleton

/** Network-backed repositories are faked; purely local ones keep their real implementation. */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class],
)
object TestRepositoryModule {
    @Provides @Singleton
    fun provideAuthRepository(fake: FakeAuthRepository): AuthRepository = fake

    @Provides @Singleton
    fun provideCanyonRepository(fake: FakeCanyonRepository): CanyonRepository = fake

    @Provides @Singleton
    fun provideDebitRepository(fake: FakeDebitRepository): DebitRepository = fake

    @Provides @Singleton
    fun provideDebitPredictionRepository(fake: FakeDebitPredictionRepository): DebitPredictionRepository = fake

    @Provides @Singleton
    fun provideEdfPracticabilityRepository(fake: FakeEdfPracticabilityRepository): EdfPracticabilityRepository = fake

    @Provides @Singleton
    fun provideFavoritesRepository(fake: FakeFavoritesRepository): FavoritesRepository = fake

    @Provides @Singleton
    fun provideForumRepository(fake: FakeForumRepository): ForumRepository = fake

    @Provides @Singleton
    fun providePhotoRepository(fake: FakePhotoRepository): PhotoRepository = fake

    @Provides @Singleton
    fun provideDebitSubmissionRepository(fake: FakeDebitSubmissionRepository): DebitSubmissionRepository = fake

    @Provides @Singleton
    fun provideInterestRatingRepository(fake: FakeInterestRatingRepository): InterestRatingRepository = fake

    @Provides @Singleton
    fun provideWeatherRepository(fake: FakeWeatherRepository): WeatherRepository = fake

    @Provides @Singleton
    fun provideDebitPredictionSupportRepository(impl: DebitPredictionSupportRepositoryImpl): DebitPredictionSupportRepository = impl

    @Provides @Singleton
    fun provideForumUserRepository(impl: ForumUserRepositoryImpl): ForumUserRepository = impl

    @Provides @Singleton
    fun provideNotificationCenterRepository(impl: NotificationCenterRepositoryImpl): NotificationCenterRepository = impl

    @Provides @Singleton
    fun providePreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository = impl
}
