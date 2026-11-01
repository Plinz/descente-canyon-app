package fr.descentecanyon.app.sync

import android.util.Log
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the copy of the member's flow reports up to date: the logbook shows the flow of an outing
 * by reading that copy, since the flow itself only exists on the site. The list is a slow page: it
 * is read again after a report of this app reached the site, and otherwise at most every
 * [SiteReportsRefreshPolicy.MAX_AGE_MS] (a reconnection in the mountains does not reload it each time).
 */
@Singleton
class SiteReportsRefresher @Inject constructor(
    private val siteAccountRepository: SiteAccountRepository,
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val authRepository: AuthRepository,
    private val clock: Clock,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                connectivityObserver.observe(),
                authRepository.authState.map { (it as? AuthState.Connected)?.username },
                debitSubmissionRepository.observeLastSentAt(),
            ) { online, username, lastSentAt -> Trigger(online, username, lastSentAt) }
                .distinctUntilChanged()
                .collect { trigger ->
                    if (!trigger.online || trigger.username == null) return@collect
                    val readAt = siteAccountRepository.myObservationsReadAt()
                    if (!SiteReportsRefreshPolicy.isDue(readAt, trigger.lastSentAt, clock.millis())) return@collect
                    // The site lists a new report a moment after it was posted.
                    if (trigger.lastSentAt != null && (readAt == null || trigger.lastSentAt > readAt)) delay(LISTING_DELAY_MS)
                    suspendRunCatching { siteAccountRepository.refreshMyObservations().getOrThrow() }
                        .onFailure { Log.i(TAG, "Reports of the account not read", it) }
                }
        }
    }

    private data class Trigger(val online: Boolean, val username: String?, val lastSentAt: Long?)

    private companion object {
        const val TAG = "SiteReportsRefresher"
        const val LISTING_DELAY_MS = 3_000L
    }
}

/** When the copy of the account's reports is worth reading again. */
internal object SiteReportsRefreshPolicy {
    /** Beyond this age, the copy is read again (reports written on the website meanwhile). */
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /** Never read, read before the last report sent from the app, or older than [MAX_AGE_MS]. */
    fun isDue(readAt: Long?, lastSentAt: Long?, now: Long): Boolean = readAt == null ||
        (lastSentAt != null && lastSentAt >= readAt) ||
        now - readAt >= MAX_AGE_MS
}
