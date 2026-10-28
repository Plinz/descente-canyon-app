package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.IntRangeFilter
import fr.descentecanyon.app.domain.model.LogbookFilter
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.model.ZoneSubdivision
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCanyonsUseCaseTest {

    private val useCase = SearchCanyonsUseCase(mockk<CanyonRepository>(), mockk<LogbookRepository>())

    @Test
    fun `difficulty sort compares strongest cotation component first`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, cotation = "V1A1V"),
                canyon(id = 2, cotation = "V4A4IV"),
                canyon(id = 3, cotation = "V4A1V"),
                canyon(id = 4, cotation = "V3A3V"),
            ),
            criteria = SearchCriteria(
                query = "canyon",
                sortField = SearchSortField.DIFFICULTY,
                sortDirection = SortDirection.DESC,
            ),
        )

        assertEquals(listOf(3, 4, 1, 2), result.results.map { it.id })
    }

    @Test
    fun `the logbook filter keeps the canyons descended, or those never descended`() {
        val catalog = listOf(canyon(id = 1).copy(isDescended = true), canyon(id = 2), canyon(id = 3).copy(isDescended = true))

        val descended = useCase(catalog, SearchCriteria(logbookFilter = LogbookFilter.DESCENDED))
        val notDescended = useCase(catalog, SearchCriteria(query = "canyon", logbookFilter = LogbookFilter.NOT_DESCENDED))

        // Canyons done are few: listed without typing anything, unlike the rest of the catalog.
        assertEquals(setOf(1, 3), descended.results.map { it.id }.toSet())
        assertEquals(listOf(2), notDescended.results.map { it.id })
        assertEquals(1, SearchCriteria(logbookFilter = LogbookFilter.DESCENDED).activeFilterCount())
        assertEquals(LogbookFilter.ALL, SearchCriteria(logbookFilter = LogbookFilter.DESCENDED).clearAllFilters().logbookFilter)
    }

    @Test
    fun `cotation min max filters apply independently`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, cotation = "V2A2II"),
                canyon(id = 2, cotation = "V4A4IV"),
                canyon(id = 3, cotation = "V5A2III"),
                canyon(id = 4, cotation = "??"),
            ),
            criteria = SearchCriteria(
                verticalRange = IntRangeFilter(min = 3, max = 5),
                aquaticRange = IntRangeFilter(max = 4),
                engagementRange = IntRangeFilter(min = 3),
            ),
        )

        assertEquals(setOf(2, 3), result.results.map { it.id }.toSet())
    }

    @Test
    fun `descending numeric sort keeps unknown values last`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, longueur = 1000),
                canyon(id = 2, longueur = null),
                canyon(id = 3, longueur = 500),
            ),
            criteria = SearchCriteria(
                query = "canyon",
                sortField = SearchSortField.LENGTH,
                sortDirection = SortDirection.DESC,
            ),
        )

        assertEquals(listOf(1, 3, 2), result.results.map { it.id })
    }

    @Test
    fun `broad search is deferred until user types or filters`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1),
                canyon(id = 2),
            ),
            criteria = SearchCriteria(),
        )

        assertTrue(result.isResultListDeferred)
        assertEquals(2, result.totalResultsCount)
        assertEquals(emptyList<Int>(), result.results.map { it.id })
    }

    @Test
    fun `country and subdivision filters match multi-valued entries`() {
        val catalog = listOf(
            canyon(
                id = 1,
                pays = "France, Espagne",
                countryTokens = listOf("France", "Espagne"),
                departement = "Pyrenees-Atlantiques, Huesca",
                departmentTokens = listOf("Pyrenees-Atlantiques", "Huesca"),
                subdivisionsByCountry = mapOf(
                    "France" to listOf("Pyrenees-Atlantiques"),
                    "Espagne" to listOf("Huesca"),
                ),
            ),
        )

        val result = useCase(
            catalog = catalog,
            criteria = SearchCriteria(
                selectedCountry = "Espagne",
                selectedDepartment = "Huesca",
            ),
        )

        val franceResult = useCase(
            catalog = catalog,
            criteria = SearchCriteria(
                selectedCountry = "France",
            ),
        )

        assertEquals(listOf(1), result.results.map { it.id })
        assertEquals(listOf(1), franceResult.results.map { it.id })
        assertEquals(
            listOf(
                ZoneCountry("Espagne", 1, listOf(ZoneSubdivision("Huesca", 1))),
                ZoneCountry("France", 1, listOf(ZoneSubdivision("Pyrenees-Atlantiques", 1))),
            ),
            result.zones,
        )
    }

    @Test
    fun `distance sort orders nearest canyon first`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, latitude = 43.7000, longitude = 6.9000),
                canyon(id = 2, latitude = 43.9000, longitude = 7.3000),
                canyon(id = 3, latitude = 44.5000, longitude = 5.5000),
            ),
            criteria = SearchCriteria(
                sortField = SearchSortField.DISTANCE,
                sortDirection = SortDirection.ASC,
                userLatitude = 43.7050,
                userLongitude = 6.9050,
            ),
        )

        assertEquals(listOf(1, 2, 3), result.results.map { it.id })
    }

    @Test
    fun `distance sort disables broad defer when user location is known`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, latitude = 43.7000, longitude = 6.9000),
                canyon(id = 2, latitude = 43.9000, longitude = 7.3000),
            ),
            criteria = SearchCriteria(
                sortField = SearchSortField.DISTANCE,
                sortDirection = SortDirection.ASC,
                userLatitude = 43.7050,
                userLongitude = 6.9050,
            ),
        )

        assertEquals(false, result.isResultListDeferred)
        assertEquals(listOf(1, 2), result.results.map { it.id })
    }

    @Test
    fun `the distance filter keeps the canyons around the point, in any order, and waits for the point`() {
        val catalog = listOf(
            canyon(id = 1, interet = 2f, latitude = 43.7000, longitude = 6.9000),
            // About 37 km away.
            canyon(id = 2, interet = 4f, latitude = 43.9000, longitude = 7.3000),
            canyon(id = 3, interet = 3f, latitude = 44.5000, longitude = 5.5000),
        )
        val around =
            SearchCriteria(maxDistanceKm = 40, sortField = SearchSortField.INTEREST, userLatitude = 43.7050, userLongitude = 6.9050)

        val result = useCase(catalog = catalog, criteria = around)
        assertEquals(false, result.isResultListDeferred)
        assertEquals(listOf(2, 1), result.results.map { it.id })
        assertEquals(listOf(1), useCase(catalog = catalog, criteria = around.copy(maxDistanceKm = 20)).results.map { it.id })
        assertEquals(1, around.activeFilterCount())
        // Without the point, nothing narrows the catalog yet.
        assertEquals(
            true,
            useCase(catalog = catalog, criteria = around.copy(userLatitude = null, userLongitude = null)).isResultListDeferred,
        )
    }

    @Test
    fun `search ignores punctuation and matches tokens out of order`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, nom = "Cros-Chaumeil"),
                canyon(id = 2, nom = "Riolan"),
            ),
            criteria = SearchCriteria(query = "Chaumeil Cros"),
        )

        assertEquals(listOf(1), result.results.map { it.id })
    }

    @Test
    fun `relevance favors name matches over secondary field matches`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, nom = "Cros-Chaumeil"),
                canyon(id = 2, nom = "Riolan", searchableText = "riolan france cros chaumeil"),
            ),
            criteria = SearchCriteria(query = "Cros Chaumeil"),
        )

        assertEquals(listOf(1, 2), result.results.map { it.id })
    }

    private fun canyon(
        id: Int,
        nom: String = "Canyon $id",
        nomComplet: String = nom,
        cotation: String = "V3A3III",
        longueur: Int? = 1000,
        pays: String = "France",
        countryTokens: List<String> = listOf("France"),
        departement: String? = null,
        departmentTokens: List<String> = emptyList(),
        subdivisionsByCountry: Map<String, List<String>> = emptyMap(),
        latitude: Double? = null,
        longitude: Double? = null,
        interet: Float? = null,
        searchableText: String = listOf(nom, nomComplet, pays)
            .plus(countryTokens)
            .plus(listOfNotNull(departement))
            .plus(departmentTokens)
            .joinToString(" ")
            .normalizeForSearch(),
    ) = CanyonSearchItem(
        id = id,
        interet = interet,
        nom = nom,
        nomComplet = nomComplet,
        pays = pays,
        countryTokens = countryTokens,
        departement = departement,
        departmentTokens = departmentTokens,
        subdivisionsByCountry = subdivisionsByCountry,
        cotation = cotation,
        cotationRating = CotationRating.parse(cotation),
        longueur = longueur,
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id/test.html",
        searchableText = searchableText,
        normalizedNom = nom.normalizeForSearch(),
        normalizedNomComplet = nomComplet.normalizeForSearch(),
    )
}
