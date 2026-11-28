package fr.descentecanyon.app.domain.model

import java.time.Instant

/**
 * What the app knows of its own server (catalog updates, shared tracks). The server only speeds up
 * or completes the app: without it, or when it is switched off, everything works as before.
 */
data class AppServerState(
    /** A server address and key are built into this version of the app. */
    val isConfigured: Boolean = false,
    /** The person keeps the server on (default) or switched it off in the settings. */
    val isEnabled: Boolean = true,
    /** Catalog downloads allowed over mobile data (otherwise Wi-Fi or any unmetered network only). */
    val catalogOnMobileData: Boolean = false,
    val lastCheckedAt: Instant? = null,
    /** The last check failed (network, invalid signature...); the previous state stays in use. */
    val lastCheckFailed: Boolean = false,
    /** This version is older than the minimum the server asks for. */
    val updateRequired: Boolean = false,
    val messages: List<ServerMessage> = emptyList(),
    val features: ServerFeatures = ServerFeatures(),
    val catalog: CatalogStatus = CatalogStatus(),
) {
    /** The server can be used right now (configured and not switched off). */
    val isActive: Boolean get() = isConfigured && isEnabled

    val communityTracksAvailable: Boolean get() = isActive && features.communityTracks

    /** New canyons and changes can be proposed (the operator can close proposals from the manifest). */
    val canyonProposalsAvailable: Boolean get() = isActive && features.canyonProposals

    /** The idea box takes messages (only once the server says it does: older servers do not). */
    val feedbackAvailable: Boolean get() = isActive && features.feedback

    /** Short links of outings, stays and lists can be made (else the link carries the outing whole). */
    val sharesAvailable: Boolean get() = isActive && features.shares
}

data class ServerFeatures(
    val communityTracks: Boolean = true,
    val catalogUpdates: Boolean = true,
    val canyonProposals: Boolean = true,
    val feedback: Boolean = false,
    val shares: Boolean = false,
)

enum class ServerMessageLevel { INFO, WARNING }

/** A message published by the server, in French and usually other languages; [link] what it opens (a canyon, a page). */
data class ServerMessage(val id: String, val level: ServerMessageLevel, val texts: Map<String, String>, val link: String? = null) {
    /** The text in [language] (ISO 639-1), else in English, else in French. */
    fun textFor(language: String): String = texts[language] ?: texts["en"] ?: texts["fr"] ?: texts.values.firstOrNull().orEmpty()
}

enum class CatalogSource {
    /** The catalog built into the installed APK. */
    APP,

    /** A catalog downloaded from the server, newer than the APK's. */
    SERVER,
}

data class CatalogStatus(
    val source: CatalogSource = CatalogSource.APP,
    /** When the installed catalog data was built. */
    val dataVersion: Instant? = null,
    /** A newer catalog was downloaded and checked: it replaces the current one at the next start. */
    val pendingDataVersion: Instant? = null,
)
