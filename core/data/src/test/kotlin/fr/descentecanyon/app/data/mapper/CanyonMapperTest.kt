package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.local.catalog.entity.CanyonEntity
import fr.descentecanyon.app.data.local.catalog.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.catalog.entity.WatershedEntity
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.domain.model.GeoPointSource
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CanyonMapperTest {

    // --- Helper factories ---

    private fun canyonEntity(
        id: Int = 42,
        nom: String = "Riolan",
        nomComplet: String = "Canyon du Riolan",
        pays: String = "France",
        region: String? = "PACA",
        departement: String? = "06",
        commune: String = "Sigale",
        massif: String? = "Prealpes de Grasse",
        cotation: String = "v3a3III",
        altitudeDepart: Int? = 800,
        denivele: Int? = 250,
        longueur: Int? = 1500,
        cascadeMax: Int? = 15,
        cordeMin: Int? = 30,
        tempsApproche: String? = "45min",
        tempsDescente: String? = "3h",
        tempsRetour: String? = "30min",
        navette: String? = "non",
        interet: Float? = 4f,
        nbVotes: Int = 120,
        url: String = "/canyoning/canyon/42/",
        lastUpdated: Long = 1_000_000L,
    ) = CanyonEntity(
        id = id,
        nom = nom,
        nomComplet = nomComplet,
        pays = pays,
        region = region,
        departement = departement,
        commune = commune,
        massif = massif,
        cotation = cotation,
        altitudeDepart = altitudeDepart,
        denivele = denivele,
        longueur = longueur,
        cascadeMax = cascadeMax,
        cordeMin = cordeMin,
        tempsApproche = tempsApproche,
        tempsDescente = tempsDescente,
        tempsRetour = tempsRetour,
        navette = navette,
        interet = interet,
        nbVotes = nbVotes,
        url = url,
        lastUpdated = lastUpdated,
    )

    // --- Entity -> Domain (toDomain) ---

    @Test
    fun `toDomain maps all fields correctly`() {
        val entity = canyonEntity()
        val canyon = entity.toDomain()

        assertEquals(42, canyon.id)
        assertEquals("Riolan", canyon.nom)
        assertEquals("Canyon du Riolan", canyon.nomComplet)
        assertEquals("France", canyon.pays)
        assertEquals("PACA", canyon.region)
        assertEquals("06", canyon.departement)
        assertEquals("Sigale", canyon.commune)
        assertEquals("Prealpes de Grasse", canyon.massif)
        assertEquals("v3a3III", canyon.cotation)
        assertEquals(800, canyon.altitudeDepart)
        assertEquals(250, canyon.denivele)
        assertEquals(1500, canyon.longueur)
        assertEquals(15, canyon.cascadeMax)
        assertEquals(30, canyon.cordeMin)
        assertEquals("45min", canyon.tempsApproche)
        assertEquals("3h", canyon.tempsDescente)
        assertEquals("30min", canyon.tempsRetour)
        assertEquals("non", canyon.navette)
        assertEquals(4f, canyon.interet)
        assertEquals(120, canyon.nbVotes)
        assertEquals("/canyoning/canyon/42/", canyon.url)
        assertEquals(1_000_000L, canyon.lastUpdated)
    }

    // --- Entity -> Summary (toSummary) ---

    @Test
    fun `toSummary maps summary fields correctly`() {
        val entity = canyonEntity(interet = 3.5f)
        val summary = entity.toSummary()

        assertEquals(42, summary.id)
        assertEquals("Riolan", summary.nom)
        assertEquals("France", summary.pays)
        assertEquals("06", summary.departement)
        assertEquals("v3a3III", summary.cotation)
        assertEquals(3.5f, summary.interet)
        assertEquals("/canyoning/canyon/42/", summary.url)
    }

    @Test
    fun `interest is clamped to four stars scale`() {
        val entity = canyonEntity(interet = 4.2f)

        assertEquals(4f, entity.toDomain().interet)
        assertEquals(4f, entity.toSummary().interet)
    }

    @Test
    fun `search index round trip preserves precomputed fields`() {
        val item = canyonEntity(navette = "oui")
            .toSearchItem(representativeLat = 43.7, representativeLng = 6.9)
            .copy(subdivisionsByCountry = mapOf("France" to listOf("06")))

        val restored = item.toSearchIndexEntity().toSearchItem()

        assertEquals(item.id, restored.id)
        assertEquals(item.searchableText, restored.searchableText)
        assertEquals(item.normalizedNom, restored.normalizedNom)
        assertEquals(3, restored.cotationRating.vertical)
        assertEquals(3, restored.cotationRating.aquatic)
        assertEquals(3, restored.cotationRating.engagement)
        assertEquals(true, restored.hasNavette)
        assertEquals(43.7, restored.representativeLat)
        assertEquals(6.9, restored.representativeLng)
        assertEquals(mapOf("France" to listOf("06")), restored.subdivisionsByCountry)
    }

    // --- DebitEntity -> Debit (valid date) ---

    @Test
    fun `DebitEntity toDomain with valid ISO date`() {
        val entity = DebitEntity(
            id = 1,
            canyonId = 42,
            date = "2025-07-15",
            niveau = "CORRECT",
            auteur = "Jean",
            commentaire = "Beau debit",
        )
        val debit = entity.toDomain()

        assertEquals(1L, debit.id)
        assertEquals(42, debit.canyonId)
        assertEquals(LocalDate.of(2025, 7, 15), debit.date)
        assertEquals(NiveauDebit.CORRECT, debit.niveau)
        assertEquals("Jean", debit.auteur)
        assertEquals("Beau debit", debit.commentaire)
    }

    // --- DebitEntity -> Debit (invalid date - should not crash) ---

    @Test
    fun `DebitEntity toDomain with malformed date does not crash`() {
        val entity = DebitEntity(
            id = 2,
            canyonId = 42,
            date = "not-a-date",
            niveau = "GROS",
            auteur = null,
            commentaire = null,
        )
        val debit = entity.toDomain()

        // Should fall back to 1970-01-01 instead of throwing
        assertEquals(LocalDate.of(1970, 1, 1), debit.date)
        assertEquals(NiveauDebit.GROS, debit.niveau)
    }

    @Test
    fun `DebitEntity toDomain with empty date does not crash`() {
        val entity = DebitEntity(
            id = 3,
            canyonId = 42,
            date = "",
            niveau = "SEC",
        )
        val debit = entity.toDomain()

        assertEquals(LocalDate.of(1970, 1, 1), debit.date)
    }

    // --- DebitEntity -> Debit (unknown niveau) ---

    @Test
    fun `DebitEntity toDomain with unknown niveau falls back to INCONNU`() {
        val entity = DebitEntity(
            id = 4,
            canyonId = 42,
            date = "2025-06-01",
            niveau = "UNKNOWN_LEVEL",
        )
        val debit = entity.toDomain()

        assertEquals(NiveauDebit.INCONNU, debit.niveau)
    }

    // --- GeoPointEntity with unknown type ---

    @Test
    fun `GeoPointEntity toDomain with unknown type falls back to UNKNOWN`() {
        val entity = GeoPointEntity(
            id = 10,
            canyonId = 42,
            type = "SOME_FUTURE_TYPE",
            latitude = 43.85,
            longitude = 6.95,
            title = "Point X",
            remark = "Belvédère",
        )
        val geoPoint = entity.toDomain()

        assertEquals(GeoPointType.UNKNOWN, geoPoint.type)
        assertEquals(43.85, geoPoint.latitude, 0.001)
        assertEquals(6.95, geoPoint.longitude, 0.001)
        assertEquals("Point X", geoPoint.title)
        assertEquals("Belvédère", geoPoint.remark)
    }

    @Test
    fun `GeoPointEntity toDomain with valid type maps correctly`() {
        val entity = GeoPointEntity(
            id = 11,
            canyonId = 42,
            type = "PARKING_AVAL",
            latitude = 43.85,
            longitude = 6.95,
            title = "Parking bas",
        )
        val geoPoint = entity.toDomain()

        assertEquals(GeoPointType.PARKING_AVAL, geoPoint.type)
        assertEquals(GeoPointSource.SITE, geoPoint.source)
    }

    @Test
    fun `GeoPointEntity toDomain keeps where a recovered point comes from`() {
        fun sourceOf(source: String) = GeoPointEntity(canyonId = 42, type = "ENTREE", latitude = 43.85, longitude = 6.95, source = source)
            .toDomain().source

        assertEquals(GeoPointSource.OPENCANYON, sourceOf("OPENCANYON"))
        assertEquals(GeoPointSource.TOPO, sourceOf("TOPO"))
        assertEquals(GeoPointSource.ESTIMATED, sourceOf("ESTIMATED"))
        // Never shown as a point of the site.
        assertEquals(GeoPointSource.ESTIMATED, sourceOf("SOME_FUTURE_SOURCE"))
    }

    @Test
    fun `WatershedEntity toDomain maps area geometry and bounds`() {
        val entity = WatershedEntity(
            canyonId = 42,
            areaKm2 = 12.34,
            geometryJson = "{\"type\":\"Polygon\",\"coordinates\":[]}",
            bboxMinLongitude = 6.10,
            bboxMinLatitude = 43.70,
            bboxMaxLongitude = 6.20,
            bboxMaxLatitude = 43.80,
        )

        val watershed = entity.toDomain()

        assertEquals(12.34, watershed.areaKm2 ?: 0.0, 0.001)
        assertEquals("{\"type\":\"Polygon\",\"coordinates\":[]}", watershed.geometryJson)
        assertEquals(6.10, watershed.bounds?.minLongitude ?: 0.0, 0.001)
        assertEquals(43.70, watershed.bounds?.minLatitude ?: 0.0, 0.001)
        assertEquals(6.20, watershed.bounds?.maxLongitude ?: 0.0, 0.001)
        assertEquals(43.80, watershed.bounds?.maxLatitude ?: 0.0, 0.001)
    }
}
