package fr.descentecanyon.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class IntRangeFilter(val min: Int? = null, val max: Int? = null) {
    fun isActive(): Boolean = min != null || max != null

    fun matches(value: Int?): Boolean {
        if (!isActive()) return true
        value ?: return false
        if (min != null && value < min) return false
        if (max != null && value > max) return false
        return true
    }
}

@Serializable
data class SearchCriteria(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    /** Canyons of the logbook only, or those never descended. */
    val logbookFilter: LogbookFilter = LogbookFilter.ALL,
    val selectedCountry: String? = null,
    val selectedDepartment: String? = null,
    val verticalRange: IntRangeFilter = IntRangeFilter(),
    val aquaticRange: IntRangeFilter = IntRangeFilter(),
    val engagementRange: IntRangeFilter = IntRangeFilter(),
    val interestMin: Float? = null,
    val regulationOnly: Boolean = false,
    val shuttleOnly: Boolean = false,
    val altitudeRange: IntRangeFilter = IntRangeFilter(),
    val elevationRange: IntRangeFilter = IntRangeFilter(),
    val lengthRange: IntRangeFilter = IntRangeFilter(),
    val maxWaterfallRange: IntRangeFilter = IntRangeFilter(),
    val ropeRange: IntRangeFilter = IntRangeFilter(),
    /** At most this far in a straight line from the point distances are measured from, when it is known. */
    val maxDistanceKm: Int? = null,
    val sortField: SearchSortField = SearchSortField.RELEVANCE,
    val sortDirection: SortDirection = SortDirection.DESC,
    /** The point distances are measured from: the user's position, or the place of a stay. */
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
) {
    /** The point distances are measured from is known: the distance filter and sort apply. */
    val hasReferencePoint: Boolean get() = userLatitude != null && userLongitude != null

    fun hasCotationFilter(): Boolean = verticalRange.isActive() || aquaticRange.isActive() || engagementRange.isActive()

    fun hasAdvancedFilters(): Boolean = hasCotationFilter() ||
        interestMin != null ||
        regulationOnly ||
        shuttleOnly ||
        altitudeRange.isActive() ||
        elevationRange.isActive() ||
        lengthRange.isActive() ||
        maxWaterfallRange.isActive() ||
        ropeRange.isActive()

    fun shouldDeferBroadResults(): Boolean {
        val canSortByDistance = sortField == SearchSortField.DISTANCE && hasReferencePoint
        val isAroundPoint = maxDistanceKm != null && hasReferencePoint
        return query.normalizeForSearch().length < 2 &&
            !isAroundPoint &&
            !favoritesOnly &&
            logbookFilter != LogbookFilter.DESCENDED &&
            selectedCountry == null &&
            selectedDepartment == null &&
            !hasAdvancedFilters() &&
            !canSortByDistance
    }

    fun activeFilterCount(): Int {
        var count = 0
        if (favoritesOnly) count++
        if (logbookFilter != LogbookFilter.ALL) count++
        if (selectedCountry != null) count++
        if (selectedDepartment != null) count++
        if (verticalRange.isActive()) count++
        if (aquaticRange.isActive()) count++
        if (engagementRange.isActive()) count++
        if (interestMin != null) count++
        if (regulationOnly) count++
        if (shuttleOnly) count++
        if (altitudeRange.isActive()) count++
        if (elevationRange.isActive()) count++
        if (lengthRange.isActive()) count++
        if (maxWaterfallRange.isActive()) count++
        if (ropeRange.isActive()) count++
        if (maxDistanceKm != null) count++
        return count
    }

    fun clearAllFilters(): SearchCriteria = copy(
        favoritesOnly = false,
        logbookFilter = LogbookFilter.ALL,
        selectedCountry = null,
        selectedDepartment = null,
        verticalRange = IntRangeFilter(),
        aquaticRange = IntRangeFilter(),
        engagementRange = IntRangeFilter(),
        interestMin = null,
        regulationOnly = false,
        shuttleOnly = false,
        altitudeRange = IntRangeFilter(),
        elevationRange = IntRangeFilter(),
        lengthRange = IntRangeFilter(),
        maxWaterfallRange = IntRangeFilter(),
        ropeRange = IntRangeFilter(),
        maxDistanceKm = null,
    )

    companion object {
        /** The distances offered for [maxDistanceKm]. */
        val MAX_DISTANCES_KM = listOf(20, 40, 60, 100)
    }
}

/** Filter on the user's logbook. */
@Serializable
enum class LogbookFilter {
    ALL,

    /** Canyons with at least one outing in the logbook. */
    DESCENDED,

    /** Canyons never descended: ideas for the next outings. */
    NOT_DESCENDED,
}

@Serializable
enum class SearchSortField {
    RELEVANCE,
    NAME,
    INTEREST,
    POPULARITY,
    DIFFICULTY,
    ELEVATION,
    LENGTH,
    MAX_WATERFALL,
    DISTANCE,
}

@Serializable
enum class SortDirection {
    ASC,
    DESC,
}
