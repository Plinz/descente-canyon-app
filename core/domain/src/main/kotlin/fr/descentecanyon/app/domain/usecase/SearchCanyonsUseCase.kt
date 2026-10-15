package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchResultSet
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.matches
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.model.subdivisionsFor
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject

class SearchCanyonsUseCase @Inject constructor(private val canyonRepository: CanyonRepository) {
    fun observeCatalog() = canyonRepository.observeSearchCatalog()

    operator fun invoke(catalog: List<CanyonSearchItem>, criteria: SearchCriteria): SearchResultSet {
        val normalizedQuery = criteria.query.normalizeForSearch()
        val queryTokens = normalizedQuery.searchTokens()
        val shouldDeferResults = criteria.shouldDeferBroadResults()

        val geoOptionMatches = catalog.asSequence()
            .filter { matchesQuery(it, normalizedQuery, queryTokens) }
            .toList()

        val baseMatches = catalog.asSequence()
            .filter { matchesBaseFilters(it, criteria, normalizedQuery, queryTokens) }
            .toList()

        val availableCountries = geoOptionMatches.asSequence()
            .flatMap { it.countryTokens.asSequence() }
            .distinct()
            .sorted()
            .toList()

        val countryOptionMatches = geoOptionMatches.asSequence()
            .filter { matchesCountry(it, criteria.selectedCountry) }
            .toList()

        val countryMatches = baseMatches.asSequence()
            .filter { matchesCountry(it, criteria.selectedCountry) }
            .toList()

        val availableDepartments = countryOptionMatches.asSequence()
            .flatMap { it.subdivisionsFor(criteria.selectedCountry).asSequence() }
            .distinct()
            .sorted()
            .toList()

        val results = countryMatches.asSequence()
            .filter { matchesDepartment(it, criteria.selectedCountry, criteria.selectedDepartment) }
            .toList()

        if (shouldDeferResults) {
            return SearchResultSet(
                results = emptyList(),
                availableCountries = availableCountries,
                availableDepartments = availableDepartments,
                totalResultsCount = results.size,
                isResultListDeferred = true,
            )
        }

        return SearchResultSet(
            results = sortItems(results, criteria),
            availableCountries = availableCountries,
            availableDepartments = availableDepartments,
            totalResultsCount = results.size,
        )
    }

    private fun matchesBaseFilters(
        item: CanyonSearchItem,
        criteria: SearchCriteria,
        normalizedQuery: String,
        queryTokens: List<String>,
    ): Boolean = matchesQuery(item, normalizedQuery, queryTokens) &&
        (!criteria.favoritesOnly || item.isFavorite) &&
        item.cotationRating.matches(criteria) &&
        (criteria.interestMin == null || (item.interet ?: Float.NEGATIVE_INFINITY) >= criteria.interestMin) &&
        (!criteria.regulationOnly || item.hasSpecificRegulation) &&
        (!criteria.shuttleOnly || item.hasNavette) &&
        criteria.altitudeRange.matches(item.altitudeDepart) &&
        criteria.elevationRange.matches(item.denivele) &&
        criteria.lengthRange.matches(item.longueur) &&
        criteria.maxWaterfallRange.matches(item.cascadeMax) &&
        criteria.ropeRange.matches(item.cordeMin)

    private fun matchesCountry(item: CanyonSearchItem, country: String?): Boolean = country == null || item.countryTokens.any {
        it.equals(country, ignoreCase = true)
    }

    private fun matchesDepartment(item: CanyonSearchItem, country: String?, department: String?): Boolean =
        department == null || item.subdivisionsFor(country).any {
            it.equals(department, ignoreCase = true)
        }

    private fun matchesQuery(item: CanyonSearchItem, normalizedQuery: String, queryTokens: List<String>): Boolean {
        if (normalizedQuery.isBlank()) return true
        return queryTokens.all(item.searchableText::contains)
    }

    private fun sortItems(items: List<CanyonSearchItem>, criteria: SearchCriteria): List<CanyonSearchItem> {
        if (items.size <= 1) return items
        if (criteria.sortField == SearchSortField.RELEVANCE) return sortByRelevance(items, criteria)

        val comparator = when (criteria.sortField) {
            SearchSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.INTEREST -> compareNullable<CanyonSearchItem, Float> { it.interet }
                .thenBy { it.nbVotes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.POPULARITY -> compareBy<CanyonSearchItem> { it.nbVotes }
                .then(compareNullable<CanyonSearchItem, Float> { it.interet })
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.DIFFICULTY -> Comparator { left, right ->
                compareDifficultyAscending(left.cotationRating, right.cotationRating)
                    .takeIf { it != 0 }
                    ?: compareCotationFallback(left.cotationRating, right.cotationRating).takeIf { it != 0 }
                    ?: left.nom.compareTo(right.nom, ignoreCase = true)
            }

            SearchSortField.ELEVATION -> compareNullable<CanyonSearchItem, Int> { it.denivele }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.LENGTH -> compareNullable<CanyonSearchItem, Int> { it.longueur }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.MAX_WATERFALL -> compareNullable<CanyonSearchItem, Int> { it.cascadeMax }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }

            SearchSortField.DISTANCE -> compareByDistance(criteria)

            SearchSortField.RELEVANCE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom }
        }

        val sorted = items.sortedWith(comparator)
        return when (criteria.sortDirection) {
            SortDirection.ASC -> sorted
            SortDirection.DESC -> reverseKeepingUnknownsLast(sorted, criteria.sortField)
        }
    }

    private fun sortByRelevance(items: List<CanyonSearchItem>, criteria: SearchCriteria): List<CanyonSearchItem> {
        val normalizedQuery = criteria.query.normalizeForSearch()
        val sorted = items.map { item -> ScoredSearchItem(item, relevanceScore(item, normalizedQuery)) }
            .sortedWith(
                compareBy<ScoredSearchItem> { it.relevance }
                    .thenBy { it.item.interet ?: Float.NEGATIVE_INFINITY }
                    .thenBy { it.item.nbVotes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.nom },
            )
            .map { it.item }

        return when (criteria.sortDirection) {
            SortDirection.ASC -> sorted
            SortDirection.DESC -> sorted.reversed()
        }
    }

    private fun relevanceScore(item: CanyonSearchItem, normalizedQuery: String): Int {
        if (normalizedQuery.isBlank()) return 0
        val queryTokens = normalizedQuery.searchTokens()
        return when {
            item.normalizedNom == normalizedQuery -> 800
            item.normalizedNomComplet == normalizedQuery -> 750
            item.normalizedNom.startsWith(normalizedQuery) -> 700
            item.normalizedNom.contains(normalizedQuery) -> 650
            item.normalizedNom.matchesAllTokens(queryTokens) -> 600
            item.normalizedNomComplet.startsWith(normalizedQuery) -> 550
            item.normalizedNomComplet.contains(normalizedQuery) -> 500
            item.normalizedNomComplet.matchesAllTokens(queryTokens) -> 450
            item.searchableText.contains(normalizedQuery) -> 300
            else -> 200
        }
    }

    private fun compareCotationFallback(left: CotationRating, right: CotationRating): Int = compareValuesBy(
        left,
        right,
        { it.vertical ?: Int.MAX_VALUE },
        { it.aquatic ?: Int.MAX_VALUE },
        { it.engagement ?: Int.MAX_VALUE },
    )

    private fun compareDifficultyAscending(left: CotationRating, right: CotationRating): Int {
        val leftProfile = left.difficultyProfileDescending()
        val rightProfile = right.difficultyProfileDescending()
        return when {
            leftProfile == null && rightProfile == null -> 0

            leftProfile == null -> 1

            rightProfile == null -> -1

            else -> leftProfile.zip(rightProfile).firstOrNull { it.first != it.second }
                ?.let { (lv, rv) -> lv.compareTo(rv) }
                ?: 0
        }
    }

    private fun compareByDistance(criteria: SearchCriteria): Comparator<CanyonSearchItem> {
        val latitude = criteria.userLatitude
        val longitude = criteria.userLongitude
        return compareNullable<CanyonSearchItem, Double> { item ->
            if (latitude == null || longitude == null || item.representativeLat == null || item.representativeLng == null) {
                null
            } else {
                haversineKm(latitude, longitude, item.representativeLat, item.representativeLng)
            }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom }
    }

    private fun reverseKeepingUnknownsLast(sorted: List<CanyonSearchItem>, sortField: SearchSortField): List<CanyonSearchItem> {
        val (known, unknown) = when (sortField) {
            SearchSortField.INTEREST -> sorted.partition { it.interet != null }
            SearchSortField.DIFFICULTY -> sorted.partition { it.cotationRating.isKnown }
            SearchSortField.ELEVATION -> sorted.partition { it.denivele != null }
            SearchSortField.LENGTH -> sorted.partition { it.longueur != null }
            SearchSortField.MAX_WATERFALL -> sorted.partition { it.cascadeMax != null }
            SearchSortField.DISTANCE -> sorted.partition { it.representativeLat != null && it.representativeLng != null }
            else -> return sorted.reversed()
        }
        return known.reversed() + unknown
    }
}

private data class ScoredSearchItem(val item: CanyonSearchItem, val relevance: Int)

private fun String.searchTokens(): List<String> = split(' ').filter(String::isNotBlank)

private fun String.matchesAllTokens(tokens: List<String>): Boolean = tokens.all(this::contains)

private fun <T, R : Comparable<R>> compareNullable(selector: (T) -> R?): Comparator<T> = Comparator { left, right ->
    val lv = selector(left)
    val rv = selector(right)
    when {
        lv == null && rv == null -> 0
        lv == null -> 1
        rv == null -> -1
        else -> lv.compareTo(rv)
    }
}
