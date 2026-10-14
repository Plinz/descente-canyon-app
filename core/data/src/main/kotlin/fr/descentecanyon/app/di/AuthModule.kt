package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.remote.auth.CredentialStore
import fr.descentecanyon.app.data.remote.auth.EncryptedCredentialStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindCredentialStore(impl: EncryptedCredentialStore): CredentialStore
}
