package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonAround
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CompassDirection
import fr.descentecanyon.app.domain.model.EmergencyNumber
import fr.descentecanyon.app.domain.model.EmergencyNumbers
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.bearingDegrees
import fr.descentecanyon.app.domain.model.distanceToCanyonKm
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.usecase.FindCanyonsAroundUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.ui.location.DeviceFix
import java.time.Clock
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the canyon of the emergency screen was found. */
enum class EmergencyCanyonOrigin {
    /** Opened from the canyon page. */
    PAGE,

    /** The outing under way. */
    OUTING,

    /** The canyon around the current position. */
    POSITION,
}

/** What the rescuers need to know about the canyon, from the offline catalog. */
data class EmergencyCanyon(
    val id: Int,
    val name: String,
    val cotation: String,
    /** Town and department, or region. */
    val place: String,
    val country: String,
    /** "Engagement" section of the sheet: escape routes, commitment. */
    val engagement: String?,
    val points: List<GeoPoint>,
)

/** A point of the canyon, with the distance and the direction to reach it from the current position. */
data class EmergencyPoint(val point: GeoPoint, val distanceMeters: Int? = null, val direction: CompassDirection? = null)

data class EmergencyUiState(
    val canyon: EmergencyCanyon? = null,
    val origin: EmergencyCanyonOrigin? = null,
    /** The outing under way in [canyon], for its expected return. */
    val outing: PlannedOuting? = null,
    /** True while the canyon is looked for around the position. */
    val isLocatingCanyon: Boolean = false,
    /**
     * The position is too vague to tell the canyons apart (approximate location, cell towers in a
     * valley): no canyon is named, the user picks among [otherCanyons].
     */
    val isPositionImprecise: Boolean = false,
    /** Distance from the position to the route of the canyon. */
    val canyonDistanceMeters: Int? = null,
    /** Other canyons around the position, to correct the one found or to choose one when it is vague. */
    val otherCanyons: List<CanyonAround> = emptyList(),
    val numbers: List<EmergencyNumber> = listOf(EmergencyNumbers.EUROPEAN),
    /** False when the numbers of the country are not known for sure: the local number must be checked. */
    val isCountryKnown: Boolean = true,
    val position: DeviceFix? = null,
    /** Closest first with a position; escape routes first otherwise. */
    val points: List<EmergencyPoint> = emptyList(),
) {
    /** The point of the canyon closest to the position, to place the victim for the rescuers; none when far away. */
    val nearestPoint: EmergencyPoint?
        get() = points.filter { (it.distanceMeters ?: Int.MAX_VALUE) <= NEARBY_METERS }.minByOrNull { it.distanceMeters!! }

    /** Whether the position is at the canyon (or unknown): far from it, the canyon is not where help is needed. */
    val isAtCanyon: Boolean get() = (canyonDistanceMeters ?: 0) <= NEARBY_METERS

    companion object {
        const val NEARBY_METERS = 5_000
    }
}

/**
 * The emergency screen, offline: the canyon is the one of the page it was opened from, else the
 * outing under way, else the canyon around the position. Numbers follow the country of the canyon,
 * else the one of the mobile network.
 */
@HiltViewModel
class EmergencyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
    private val findCanyonsAroundUseCase: FindCanyonsAroundUseCase,
    private val outingRepository: OutingRepository,
    private val clock: Clock,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle[CANYON_ID_KEY] ?: 0

    private val _uiState = MutableStateFlow(EmergencyUiState(isLocatingCanyon = canyonId <= 0))
    val uiState: StateFlow<EmergencyUiState> = _uiState.asStateFlow()

    private var networkCountryIso: String? = null

    /** True once neither a page nor an outing gives the canyon: the position does. */
    private var locatesByPosition = false
    private var isPositionUnavailable = false

    /** Last fix the canyons around were looked for at, precise or not. */
    private var locatedAt: DeviceFix? = null
    private var userChoseCanyon = false
    private var canyonsAround: List<CanyonAround> = emptyList()

    init {
        viewModelScope.launch {
            val outing = outingRepository.observeCurrent().first()?.takeIf { it.isUnderway(clock.instant()) }
            when {
                canyonId > 0 -> show(canyonId, EmergencyCanyonOrigin.PAGE, outing)

                outing != null -> show(outing.canyonId, EmergencyCanyonOrigin.OUTING, outing)

                else -> {
                    locatesByPosition = true
                    val position = _uiState.value.position
                    when {
                        position != null -> locateCanyon(position)
                        isPositionUnavailable -> _uiState.update { it.copy(isLocatingCanyon = false) }
                    }
                    // No fix at all (deep gorge, location off): the screen stops searching, the canyon
                    // still comes with the first position.
                    delay(POSITION_TIMEOUT_MS)
                    if (locatedAt == null) _uiState.update { it.copy(isLocatingCanyon = false) }
                }
            }
        }
    }

    /** ISO code of the mobile network country, for the numbers when the canyon is not known. */
    fun setNetworkCountry(isoCode: String?) {
        networkCountryIso = isoCode
        _uiState.update { it.withNumbers() }
    }

    fun onPosition(fix: DeviceFix) {
        isPositionUnavailable = false
        _uiState.update {
            it.copy(
                position = fix,
                points = sortedPoints(it.canyon?.points.orEmpty(), fix),
                canyonDistanceMeters = it.canyon?.distanceMetersFrom(fix) ?: it.canyonDistanceMeters,
            )
        }
        if (!locatesByPosition) return
        val previous = locatedAt
        val becamePrecise = previous != null && previous.isImprecise() && !fix.isImprecise()
        if (previous == null || becamePrecise ||
            haversineKm(previous.latitude, previous.longitude, fix.latitude, fix.longitude) > RELOCATE_KM
        ) {
            locateCanyon(fix)
        }
    }

    /** No position (denied or unavailable): the canyon cannot be found around it. */
    fun onPositionUnavailable() {
        isPositionUnavailable = true
        if (locatesByPosition && locatedAt == null) _uiState.update { it.copy(isLocatingCanyon = false) }
    }

    /** The user corrects the canyon found around the position. */
    fun selectCanyon(id: Int) {
        val chosen = canyonsAround.firstOrNull { it.summary.id == id } ?: return
        userChoseCanyon = true
        viewModelScope.launch { show(id, EmergencyCanyonOrigin.POSITION, outing = null, distanceKm = chosen.distanceKm) }
    }

    private fun locateCanyon(fix: DeviceFix) {
        locatedAt = fix
        if (fix.isImprecise()) {
            // A vague fix would name the wrong canyon: the canyons it may be in are offered instead,
            // until a precise fix names it.
            if (userChoseCanyon || _uiState.value.canyon != null) return
            val uncertaintyKm = (fix.accuracyMeters ?: 0f) / 1000.0
            viewModelScope.launch {
                canyonsAround = findCanyonsAroundUseCase(
                    fix.latitude,
                    fix.longitude,
                    maxDistanceKm = (FindCanyonsAroundUseCase.MAX_DISTANCE_KM + uncertaintyKm).coerceAtMost(MAX_IMPRECISE_DISTANCE_KM),
                )
                _uiState.update { it.copy(isLocatingCanyon = false, isPositionImprecise = true).withOtherCanyons() }
            }
            return
        }
        viewModelScope.launch {
            canyonsAround = findCanyonsAroundUseCase(fix.latitude, fix.longitude)
            val current = _uiState.value.canyon?.id
            val target = canyonsAround.firstOrNull { userChoseCanyon && it.summary.id == current } ?: canyonsAround.firstOrNull()
            when {
                target == null -> _uiState.update { it.withNumbers() }
                target.summary.id != current -> show(target.summary.id, EmergencyCanyonOrigin.POSITION, outing = null, target.distanceKm)
                else -> Unit
            }
            _uiState.update { it.copy(isLocatingCanyon = false, isPositionImprecise = false).withOtherCanyons() }
        }
    }

    private fun DeviceFix.isImprecise(): Boolean = (accuracyMeters ?: 0f) > MAX_LOCATING_ACCURACY_M

    private suspend fun show(id: Int, origin: EmergencyCanyonOrigin, outing: PlannedOuting?, distanceKm: Double? = null) {
        val detail = getCanyonPreviewUseCase(id).getOrNull()
        if (detail == null) {
            _uiState.update { it.copy(isLocatingCanyon = false).withNumbers() }
            return
        }
        val canyon = detail.toEmergencyCanyon()
        _uiState.update {
            it.copy(
                canyon = canyon,
                origin = origin,
                outing = outing?.takeIf { underway -> underway.canyonId == id },
                isLocatingCanyon = false,
                canyonDistanceMeters = it.position?.let { fix -> canyon.distanceMetersFrom(fix) } ?: distanceKm?.toMeters(),
                points = sortedPoints(canyon.points, it.position),
            ).withNumbers().withOtherCanyons()
        }
    }

    private fun EmergencyUiState.withOtherCanyons(): EmergencyUiState = copy(
        otherCanyons = if (origin == EmergencyCanyonOrigin.POSITION || (canyon == null && isPositionImprecise)) {
            canyonsAround.filterNot { it.summary.id == canyon?.id }.take(MAX_OTHER_CANYONS)
        } else {
            emptyList()
        },
    )

    /** Numbers of the canyon's country, else of the mobile network's one, else 112. */
    private fun EmergencyUiState.withNumbers(): EmergencyUiState {
        val canyonNumbers = canyon?.country?.takeIf(EmergencyNumbers::isKnown)?.let(EmergencyNumbers::forCountries)
        val numbers = canyonNumbers ?: EmergencyNumbers.forIsoCode(networkCountryIso)
        return copy(numbers = numbers ?: listOf(EmergencyNumbers.EUROPEAN), isCountryKnown = numbers != null)
    }

    private fun sortedPoints(points: List<GeoPoint>, position: DeviceFix?): List<EmergencyPoint> {
        if (position == null) {
            return points.sortedBy { pointRank(it.type) }.map { EmergencyPoint(it) }
        }
        return points
            .map { point ->
                EmergencyPoint(
                    point = point,
                    distanceMeters = haversineKm(position.latitude, position.longitude, point.latitude, point.longitude).toMeters(),
                    direction = CompassDirection.of(bearingDegrees(position.latitude, position.longitude, point.latitude, point.longitude)),
                )
            }
            .sortedWith(compareBy<EmergencyPoint> { it.distanceMeters }.thenBy { pointRank(it.point.type) })
    }

    private fun pointRank(type: GeoPointType): Int = POINT_ORDER.indexOf(type).takeIf { it >= 0 } ?: POINT_ORDER.size

    private fun CanyonDetail.toEmergencyCanyon(): EmergencyCanyon = EmergencyCanyon(
        id = canyon.id,
        name = canyon.nom,
        cotation = canyon.cotation,
        place = listOfNotNull(
            canyon.commune.takeIf { it.isNotBlank() },
            (canyon.departement ?: canyon.region)?.takeIf { it.isNotBlank() },
        ).joinToString(", "),
        country = canyon.pays,
        engagement = engagement?.trim()?.takeIf { it.isNotEmpty() },
        points = geoPoints,
    )

    private fun EmergencyCanyon.distanceMetersFrom(fix: DeviceFix): Int? =
        distanceToCanyonKm(fix.latitude, fix.longitude, points)?.toMeters()

    private fun Double.toMeters(): Int = (this * 1000).roundToInt()

    companion object {
        const val CANYON_ID_KEY = "canyonId"

        /** Beyond this accuracy, the position does not tell one canyon from its neighbours. */
        const val MAX_LOCATING_ACCURACY_M = 500f

        /** The canyon found is looked for again once the user moved this far. */
        const val RELOCATE_KM = 1.0
        const val MAX_OTHER_CANYONS = 4

        /** A vague position is searched around up to this far. */
        const val MAX_IMPRECISE_DISTANCE_KM = 10.0

        /** Without any fix after this delay, the canyon is no longer shown as being searched. */
        const val POSITION_TIMEOUT_MS = 20_000L

        private val POINT_ORDER = listOf(
            GeoPointType.ECHAPPATOIRE,
            GeoPointType.SORTIE,
            GeoPointType.ENTREE,
            GeoPointType.PARKING_AVAL,
            GeoPointType.PARKING_AMONT,
            GeoPointType.POINT_REMARQUABLE,
        )
    }
}
