package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Random key of this installation, created at its first contribution to the app's server (a shared
 * track, a vote, a proposed canyon) and sent with contributions only: the server knows the author of
 * a track or a proposal by it, never by a name.
 */
@Singleton
class InstallKeyStore @Inject constructor(private val keyValueDao: KeyValueDao) {
    private val mutex = Mutex()

    suspend fun get(): String = mutex.withLock {
        keyValueDao.get(KEY)?.takeIf { it.length == LENGTH } ?: run {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            keyValueDao.put(KeyValueEntity(KEY, key))
            key
        }
    }

    private companion object {
        /** Named after the shared tracks, the first contributions that used it. */
        const val KEY = "community_install_key"
        const val LENGTH = 43
    }
}
