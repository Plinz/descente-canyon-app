package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneTreeTest {

    @Test
    fun `countries and subdivisions are sorted ignoring accents`() {
        val tree = buildZoneTree(
            listOf(
                item(1, mapOf("France" to listOf("Isère"))),
                item(2, mapOf("Écosse" to listOf("Highland"))),
                item(3, mapOf("France" to listOf("Ariège"))),
                item(4, mapOf("Espagne" to listOf("Huesca"))),
                item(5, mapOf("France" to listOf("Aude"))),
            ),
        )

        assertEquals(listOf("Écosse", "Espagne", "France"), tree.map { it.name })
        assertEquals(listOf("Ariège", "Aude", "Isère"), tree.last().subdivisions.map { it.name })
    }

    @Test
    fun `a border canyon counts in each country and a repeated item counts each time`() {
        val border = item(1, mapOf("France" to listOf("Pyrénées-Atlantiques"), "Espagne" to listOf("Huesca")))
        val guara = item(2, mapOf("Espagne" to listOf("Huesca")))

        val tree = buildZoneTree(listOf(border, guara, guara))

        assertEquals(
            listOf(
                ZoneCountry("Espagne", 3, listOf(ZoneSubdivision("Huesca", 3))),
                ZoneCountry("France", 1, listOf(ZoneSubdivision("Pyrénées-Atlantiques", 1))),
            ),
            tree,
        )
    }

    private fun item(id: Int, subdivisionsByCountry: Map<String, List<String>>) = CanyonSearchItem(
        id = id,
        nom = "Canyon $id",
        nomComplet = "Canyon $id",
        pays = subdivisionsByCountry.keys.joinToString(", "),
        countryTokens = subdivisionsByCountry.keys.toList(),
        departmentTokens = subdivisionsByCountry.values.flatten(),
        subdivisionsByCountry = subdivisionsByCountry,
        cotation = "V3A3III",
        cotationRating = CotationRating.parse("V3A3III"),
        url = "/canyoning/canyon/$id/",
        searchableText = "canyon $id",
        normalizedNom = "canyon $id",
        normalizedNomComplet = "canyon $id",
    )
}
