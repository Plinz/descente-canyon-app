package fr.descentecanyon.app.domain.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** The part of a canyon page a link opens: one of its tabs, or its map. */
enum class CanyonSection(val path: String) {
    TOPO("topo"),
    CONDITIONS("conditions"),
    PHOTOS("photos"),
    DEBITS("debits"),
    MAP("carte"),
    ;

    companion object {
        fun ofPath(path: String): CanyonSection? = entries.firstOrNull { it.path == path }
    }
}

/** What a link of the app's own domain opens in the app. */
sealed interface AppLink {
    /** A canyon page, on [section] (its first tab when null). */
    data class Canyon(val canyonId: Int, val section: CanyonSection? = null) : AppLink

    /** The map of a canyon, on the point nearest to [latitude], [longitude] or on the shared GPS track [trackId]. */
    data class CanyonMap(val canyonId: Int, val latitude: Double? = null, val longitude: Double? = null, val trackId: String? = null) :
        AppLink

    /** Something shared through the server (an outing, a stay, a list of canyons), by its short code. */
    data class Share(val code: String) : AppLink

    /** An outing carried whole by the link, for a QR code read without network. */
    data class OutingInLink(val canyonId: Int, val payload: String) : AppLink
}

/**
 * The links of the app's domain ([host], "canyon.aduquennoy.fr"): built for sharing, read when a
 * link opens the app. A canyon link carries a readable name the app ignores: /c/2895-riolan.
 */
class AppLinks(val host: String) {
    val isConfigured: Boolean get() = host.isNotBlank()

    private val base: String get() = "https://$host"

    fun canyon(canyonId: Int, name: String, section: CanyonSection? = null): String? {
        if (!isConfigured) return null
        val suffix = section?.let { "/${it.path}" }.orEmpty()
        return "$base${canyonPath(canyonId, name)}$suffix"
    }

    /** The map of a canyon on one of its points. */
    fun canyonPoint(canyonId: Int, name: String, latitude: Double, longitude: Double): String? = canyon(canyonId, name, CanyonSection.MAP)
        ?.let { "$it?lat=${coordinate(latitude)}&lon=${coordinate(longitude)}" }

    /** The map of a canyon on a GPS track shared on the server. */
    fun canyonTrack(canyonId: Int, name: String, trackId: String): String? = canyon(canyonId, name, CanyonSection.MAP)
        ?.takeIf { TRACK_ID.matches(trackId) }
        ?.let { "$it?trace=$trackId" }

    fun share(code: String): String? = if (isConfigured && SHARE_CODE.matches(code)) "$base/s/$code" else null

    /** An outing whole in the link: the canyon in the path for the link previews, the outing after the #, never sent to a server. */
    fun outingInLink(canyonId: Int, name: String, payload: String): String? =
        if (isConfigured) "$base/o/${canyonPath(canyonId, name).removePrefix("/c/")}#$payload" else null

    /** What [url] opens, null when it is not a link of this domain or not one the app knows. */
    fun parse(url: String): AppLink? {
        if (!isConfigured) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        val uriHost = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uriHost != host.lowercase(Locale.ROOT) && uriHost != "www.${host.lowercase(Locale.ROOT)}") return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = queryOf(uri.rawQuery)
        return when (segments.firstOrNull()) {
            "c" -> parseCanyon(segments.drop(1), query)

            "s" -> segments.getOrNull(1)?.takeIf { segments.size == 2 && SHARE_CODE.matches(it) }?.let(AppLink::Share)

            "o" -> {
                val canyonId = segments.getOrNull(1)?.takeIf { segments.size == 2 }?.let(::canyonIdOf)
                // The outing after the #, or in "p" when a page passed the link on (an intent link has no # of its own).
                val payload = (uri.rawFragment ?: query["p"])?.takeIf { OUTING_PAYLOAD.matches(it) }
                if (canyonId != null && payload != null) AppLink.OutingInLink(canyonId, payload) else null
            }

            else -> null
        }
    }

    private fun parseCanyon(segments: List<String>, query: Map<String, String>): AppLink? {
        val canyonId = segments.firstOrNull()?.let(::canyonIdOf) ?: return null
        if (segments.size > 2) return null
        val section = segments.getOrNull(1)?.let { CanyonSection.ofPath(it.lowercase(Locale.ROOT)) ?: return null }
        if (section != CanyonSection.MAP) return AppLink.Canyon(canyonId, section)
        val latitude = query["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val longitude = query["lon"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        val trackId = query["trace"]?.takeIf { TRACK_ID.matches(it) }
        return if (latitude != null && longitude != null) {
            AppLink.CanyonMap(canyonId, latitude, longitude)
        } else {
            AppLink.CanyonMap(canyonId, trackId = trackId)
        }
    }

    companion object {
        /** No domain built in: shares fall back to the site's address, and no link opens the app. */
        val NONE = AppLinks("")

        /** The codes the server gives to what it keeps for a shared link. */
        val SHARE_CODE = Regex("^[A-Za-z0-9]{6,16}$")
        val TRACK_ID = Regex("^t[A-Za-z0-9_-]{8,24}$")

        /** Base64url of a compressed outing, a few hundred characters. */
        val OUTING_PAYLOAD = Regex("^[A-Za-z0-9_.-]{8,4096}$")
        private val CANYON_REF = Regex("^(\\d{1,9})(?:-[A-Za-z0-9-]{0,80})?$")
        private const val MAX_SLUG = 60

        /** "Gorges du Riolan (aval)" → "gorges-du-riolan-aval". */
        fun slug(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace("œ", "oe")
            .replace("æ", "ae")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_SLUG)
            .trimEnd('-')

        private fun canyonPath(canyonId: Int, name: String): String = slug(name).let {
            if (it.isEmpty()) "/c/$canyonId" else "/c/$canyonId-$it"
        }

        private fun canyonIdOf(ref: String): Int? = CANYON_REF.matchEntire(ref)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.5f", value)

        private fun queryOf(rawQuery: String?): Map<String, String> = rawQuery.orEmpty().split('&').mapNotNull { part ->
            val name = part.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val value = part.substringAfter('=', "")
            decode(name) to decode(value)
        }.toMap()

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

        /** For a link put in another link's query (the referrer of an installation). */
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
