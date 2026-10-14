package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.IntRangeFilter
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.CanyonRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCanyonsUseCaseTest {

    private val useCase = SearchCanyonsUseCase(mockk<CanyonRepository>())

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
        assertEquals(listOf("Espagne", "France"), result.availableCountries)
        assertEquals(listOf("Huesca"), result.availableDepartments)
        assertEquals(listOf("Pyrenees-Atlantiques"), franceResult.availableDepartments)
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
        searchableText: String = listOf(nom, nomComplet, pays)
            .plus(countryTokens)
            .plus(listOfNotNull(departement))
            .plus(departmentTokens)
            .joinToString(" ")
            .normalizeForSearch(),
    ) = CanyonSearchItem(
        id = id,
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
