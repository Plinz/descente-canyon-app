package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.ShareRequestDto
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.PublishedShare
import fr.descentecanyon.app.domain.model.ReceivedShare
import fr.descentecanyon.app.domain.model.ShareCodec
import fr.descentecanyon.app.domain.model.SharedContent
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.ShareRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Short links on the app's server; reading one needs only the server built in, making one needs it on and offering them. */
@Singleton
class ShareRepositoryImpl @Inject constructor(
    private val client: BackendClient,
    private val config: BackendConfig,
    private val installKeys: InstallKeyStore,
    private val appServer: AppServerRepository,
) : ShareRepository {

    override fun observeAvailable(): Flow<Boolean> = appServer.observeState().map { it.sharesAvailable }.distinctUntilChanged()

    override suspend fun publish(content: SharedContent, code: String?): Result<PublishedShare> = suspendRunCatching {
        if (!config.isConfigured || !observeAvailable().first()) appError(AppError.DataUnavailable)
        val request = ShareRequestDto(kind = ShareCodec.kindOf(content), payload = ShareCodec.encode(content))
        val key = installKeys.get()
        val dto = if (code == null) {
            client.createShare(request, key)
        } else {
            try {
                client.updateShare(code, request, key)
            } catch (exception: AppException) {
                // A link of this phone that expired or was deleted: a new one.
                if (exception.error != AppError.NotFound) throw exception
                client.createShare(request, key)
            }
        }
        PublishedShare(code = dto.code, url = dto.url, version = dto.version)
    }

    override suspend fun fetch(code: String): Result<ReceivedShare> = suspendRunCatching {
        // Opening someone's link needs the server, even when this phone switched off its other uses.
        if (!config.isConfigured) appError(AppError.DataUnavailable)
        val dto = client.fetchShare(code)
        val content = dto.payload?.let { ShareCodec.decode(dto.kind, it) } ?: appError(AppError.UnexpectedResponse)
        ReceivedShare(code = dto.code, version = dto.version, content = content)
    }

    override suspend fun delete(code: String): Result<Unit> = suspendRunCatching {
        if (!config.isConfigured) appError(AppError.DataUnavailable)
        client.deleteShare(code, installKeys.get())
    }
}
