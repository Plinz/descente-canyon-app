package fr.descentecanyon.app.domain.model

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The payloads of the shared links, as the server keeps them and its pages read them (version 1):
 * enum names, ISO days, "HH:mm" times. An outing can also travel whole in a link (after its #,
 * for a QR code read without network): its JSON deflated, in base64url.
 */
object ShareCodec {
    const val VERSION = 1
    const val KIND_OUTING = "OUTING"
    const val KIND_STAY = "STAY"
    const val KIND_LIST = "LIST"

    /** Beyond this, a link that carries an outing whole is refused (a QR code would not be read). */
    private const val MAX_PACKED = 4096
    private const val MAX_INFLATED = 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun kindOf(content: SharedContent): String = when (content) {
        is SharedContent.Outing -> KIND_OUTING
        is SharedContent.Stay -> KIND_STAY
        is SharedContent.CanyonList -> KIND_LIST
    }

    fun encode(content: SharedContent): JsonElement = when (content) {
        is SharedContent.Outing -> json.encodeToJsonElement(OutingDto.serializer(), OutingDto.of(content.outing))

        is SharedContent.Stay -> json.encodeToJsonElement(StayDto.serializer(), StayDto.of(content.stay))

        is SharedContent.CanyonList -> json.encodeToJsonElement(
            ListDto.serializer(),
            ListDto(title = content.title, canyonIds = content.canyonIds),
        )
    }

    /** What [payload] of [kind] holds; null when this version of the app cannot read it. */
    fun decode(kind: String, payload: JsonElement): SharedContent? = runCatching {
        if (payload !is JsonObject) return null
        when (kind) {
            KIND_OUTING -> json.decodeFromJsonElement(OutingDto.serializer(), payload).toDomain()?.let(SharedContent::Outing)
            KIND_STAY -> json.decodeFromJsonElement(StayDto.serializer(), payload).toDomain()?.let(SharedContent::Stay)
            KIND_LIST -> json.decodeFromJsonElement(ListDto.serializer(), payload).toDomain()
            else -> null
        }
    }.getOrNull()

    /** The outing whole, for the part of a link after its #. */
    fun pack(outing: SharedOuting): String {
        val bytes = json.encodeToString(OutingDto.serializer(), OutingDto.of(outing)).encodeToByteArray()
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    fun unpack(packed: String): SharedOuting? = runCatching {
        if (packed.length > MAX_PACKED) return null
        val bytes = Base64.getUrlDecoder().decode(packed)
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val read = inflater.inflate(buffer)
            if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_INFLATED) return null
        }
        inflater.end()
        json.decodeFromString(OutingDto.serializer(), output.toByteArray().decodeToString()).toDomain()
    }.getOrNull()

    /** A short digest of what a link would show: it tells when an outing changed since its link was made. */
    fun fingerprint(content: SharedContent): String {
        val text = json.encodeToString(JsonElement.serializer(), encode(content))
        return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class PlaceDto(val name: String, val lat: Double, val lon: Double) {
        fun toDomain() = SharedPlace(name.take(MAX_PLACE_NAME), lat, lon)

        companion object {
            fun of(place: SharedPlace) = PlaceDto(place.name.take(MAX_PLACE_NAME), place.latitude, place.longitude)
        }
    }

    @Serializable
    private data class NextDto(val canyonId: Int, val link: String, val adjustments: Map<String, Int> = emptyMap())

    @Serializable
    private data class TimingDto(
        val prep: Int? = null,
        val packUp: Int? = null,
        val shuttleHandling: Int? = null,
        val medium: Double? = null,
        val large: Double? = null,
        val huge: Double? = null,
        val beginners: Double? = null,
        val discovery: Double? = null,
        val beginnersPrep: Double? = null,
        val earlier: Int? = null,
        val later: Int? = null,
        val pace: Double? = null,
    ) {
        fun toDomain(): SharedTiming {
            val default = OutingSettings.DEFAULT
            val settings = OutingSettings(
                prepMinutes = prep ?: default.prepMinutes,
                packUpMinutes = packUp ?: default.packUpMinutes,
                shuttleHandlingMinutes = shuttleHandling ?: default.shuttleHandlingMinutes,
                mediumGroupFactor = medium ?: default.mediumGroupFactor,
                largeGroupFactor = large ?: default.largeGroupFactor,
                hugeGroupFactor = huge ?: default.hugeGroupFactor,
                beginnersFactor = beginners ?: default.beginnersFactor,
                discoveryFactor = discovery ?: default.discoveryFactor,
                beginnersPrepFactor = beginnersPrep ?: default.beginnersPrepFactor,
                earlierPercent = earlier ?: default.earlierPercent,
                laterPercent = later ?: default.laterPercent,
                usePersonalPace = pace != null,
            ).sanitized()
            val ratio = pace?.coerceIn(PersonalPace.MIN_RATIO, PersonalPace.MAX_RATIO)
            return SharedTiming(settings, ratio)
        }

        companion object {
            fun of(timing: SharedTiming) = timing.settings.let { s ->
                TimingDto(
                    prep = s.prepMinutes,
                    packUp = s.packUpMinutes,
                    shuttleHandling = s.shuttleHandlingMinutes,
                    medium = s.mediumGroupFactor,
                    large = s.largeGroupFactor,
                    huge = s.hugeGroupFactor,
                    beginners = s.beginnersFactor,
                    discovery = s.discoveryFactor,
                    beginnersPrep = s.beginnersPrepFactor,
                    earlier = s.earlierPercent,
                    later = s.laterPercent,
                    pace = timing.personalRatio,
                )
            }
        }
    }

    @Serializable
    private data class SummaryDto(
        val names: List<String> = emptyList(),
        val meet: String? = null,
        val walk: String? = null,
        val back: String? = null,
        val backLatest: String? = null,
    ) {
        fun toDomain() = SharedOutingSummary(
            names = names.map { it.take(MAX_NAME) }.take(OutingChoices.MAX_CANYONS),
            meet = meet?.let(::timeOf),
            walk = walk?.let(::timeOf),
            back = back?.let(::timeOf),
            backLatest = backLatest?.let(::timeOf),
        )

        companion object {
            fun of(summary: SharedOutingSummary) = SummaryDto(
                names = summary.names.map { it.take(MAX_NAME) },
                meet = summary.meet?.let(::timeText),
                walk = summary.walk?.let(::timeText),
                back = summary.back?.let(::timeText),
                backLatest = summary.backLatest?.let(::timeText),
            )
        }
    }

    @Serializable
    private data class OutingDto(
        val v: Int? = VERSION,
        val canyonId: Int,
        val next: List<NextDto> = emptyList(),
        val date: String? = null,
        val anchorTime: String,
        val meetAtPlace: Boolean = false,
        val place: PlaceDto? = null,
        val groupSize: Int,
        val groupLevel: String,
        val withShuttle: Boolean? = null,
        val adjustments: Map<String, Int> = emptyMap(),
        val timing: TimingDto? = null,
        val note: String = "",
        val summary: SummaryDto = SummaryDto(),
    ) {
        fun toDomain(): SharedOuting? {
            if (canyonId <= 0 || (v != null && v > VERSION)) return null
            val summary = summary.toDomain()
            return SharedOuting(
                canyonId = canyonId,
                next = next.take(OutingChoices.MAX_CANYONS - 1).mapIndexedNotNull { index, canyon ->
                    val link = CanyonLink.entries.firstOrNull { it.name == canyon.link } ?: return@mapIndexedNotNull null
                    SharedNextCanyon(canyon.canyonId, summary.names.getOrNull(index + 1).orEmpty(), link, phasesOf(canyon.adjustments))
                },
                date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return null },
                anchorTime = timeOf(anchorTime) ?: return null,
                meetAtPlace = meetAtPlace && place != null,
                place = place?.toDomain(),
                groupSize = groupSize.coerceIn(OutingSettings.GROUP_SIZE_RANGE),
                groupLevel = GroupLevel.entries.firstOrNull { it.name == groupLevel } ?: GroupLevel.AUTONOMOUS,
                withShuttle = withShuttle,
                adjustments = phasesOf(adjustments),
                timing = timing?.toDomain(),
                note = note.take(SharedOuting.MAX_NOTE),
                summary = summary,
            )
        }

        companion object {
            fun of(outing: SharedOuting, inStay: Boolean = false) = OutingDto(
                v = if (inStay) null else VERSION,
                canyonId = outing.canyonId,
                next = outing.next.map { NextDto(it.canyonId, it.link.name, it.adjustments.mapKeys { (phase, _) -> phase.name }) },
                date = outing.date?.toString(),
                anchorTime = timeText(outing.anchorTime),
                meetAtPlace = outing.meetAtPlace,
                place = if (inStay) null else outing.place?.takeIf { outing.meetAtPlace }?.let(PlaceDto::of),
                groupSize = outing.groupSize,
                groupLevel = outing.groupLevel.name,
                withShuttle = outing.withShuttle,
                adjustments = outing.adjustments.mapKeys { (phase, _) -> phase.name },
                timing = outing.timing?.let(TimingDto::of),
                note = outing.note.take(SharedOuting.MAX_NOTE),
                summary = SummaryDto.of(outing.summary),
            )
        }
    }

    @Serializable
    private data class StayDto(
        val v: Int = VERSION,
        val place: PlaceDto,
        val start: String,
        val end: String,
        val outings: List<OutingDto> = emptyList(),
    ) {
        fun toDomain(): SharedStay? {
            if (v > VERSION) return null
            val first = runCatching { LocalDate.parse(start) }.getOrNull() ?: return null
            val last = runCatching { LocalDate.parse(end) }.getOrNull() ?: return null
            if (last.isBefore(first)) return null
            // The outings of a stay that meet at a place meet at the stay's.
            val days = outings.mapNotNull { it.copy(v = null, place = place.takeIf { _ -> it.meetAtPlace }).toDomain() }
            return SharedStay(place.toDomain(), first, last, days.filter { it.date != null && it.date in first..last })
        }

        companion object {
            fun of(stay: SharedStay) = StayDto(
                place = PlaceDto.of(stay.place),
                start = stay.start.toString(),
                end = stay.end.toString(),
                outings = stay.outings.map { OutingDto.of(it, inStay = true) },
            )
        }
    }

    @Serializable
    private data class ListDto(val v: Int = VERSION, val title: String = "", @SerialName("canyonIds") val canyonIds: List<Int>) {
        fun toDomain(): SharedContent.CanyonList? = if (v > VERSION) {
            null
        } else {
            SharedContent.CanyonList(title.take(MAX_LIST_TITLE), canyonIds.filter { it > 0 }.distinct())
        }
    }

    private const val MAX_NAME = 120
    private const val MAX_PLACE_NAME = 80
    const val MAX_LIST_TITLE = 80

    private fun timeText(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    private fun timeOf(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()?.withSecond(0)?.withNano(0)

    private fun phasesOf(adjustments: Map<String, Int>): Map<OutingPhase, Int> = adjustments.mapNotNull { (name, minutes) ->
        OutingPhase.entries.firstOrNull { it.name == name }?.let { it to minutes }
    }.toMap()
}
