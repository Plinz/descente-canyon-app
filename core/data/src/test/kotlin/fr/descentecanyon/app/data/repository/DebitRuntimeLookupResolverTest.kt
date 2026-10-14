package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.DebitRuntimeLookups
import fr.descentecanyon.app.domain.model.RuntimeLookupEntry
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DebitRuntimeLookupResolverTest {

    @Test
    fun `returns direct canyon lookup when available`() {
        val lookups = runtimeLookups(
            canyons = mapOf(
                42 to RuntimeLookupEntry(
                    regionKey = "Auvergne-Rhone-Alpes",
                    massifKey = "Vercors",
                    values = mapOf("canyonPriorHigh" to 0.42, "canyonPastObsCount" to 12.0),
                ),
            ),
        )

        val resolved = DebitRuntimeLookupResolver.resolve(canyon(), lookups)

        assertEquals(RuntimeLookupSource.CANYON, resolved.lookupSource)
        assertEquals(0.42, resolved.featureValues.getValue("canyonPriorHigh"), 0.000001)
        assertEquals(12.0, resolved.featureValues.getValue("canyonPastObsCount"), 0.000001)
    }

    @Test
    fun `falls back to massif and region values when canyon lookup is missing`() {
        val lookups = runtimeLookups(
            defaults = mapOf("historicallySnowmeltCanyon" to 0.0),
            global = mapOf("globalPriorHigh" to 0.12),
            regions = mapOf(
                "Auvergne-Rhone-Alpes" to mapOf("regionPriorHigh" to 0.18),
            ),
            massifs = mapOf(
                "Vercors" to mapOf("massifPriorHigh" to 0.09),
            ),
        )

        val resolved = DebitRuntimeLookupResolver.resolve(canyon(), lookups)

        assertEquals(RuntimeLookupSource.MASSIF, resolved.lookupSource)
        assertEquals(0.12, resolved.featureValues.getValue("globalPriorHigh"), 0.000001)
        assertEquals(0.18, resolved.featureValues.getValue("regionPriorHigh"), 0.000001)
        assertEquals(0.09, resolved.featureValues.getValue("massifPriorHigh"), 0.000001)
        assertEquals(0.0, resolved.featureValues.getValue("historicallySnowmeltCanyon"), 0.000001)
    }

    private fun runtimeLookups(
        defaults: Map<String, Double> = emptyMap(),
        global: Map<String, Double> = emptyMap(),
        regions: Map<String, Map<String, Double>> = emptyMap(),
        massifs: Map<String, Map<String, Double>> = emptyMap(),
        canyons: Map<Int, RuntimeLookupEntry> = emptyMap(),
    ): DebitRuntimeLookups = DebitRuntimeLookups(
        schemaVersion = 1,
        labels = listOf("HIGH", "LOW", "MEDIUM"),
        lookupFeatureNames = listOf("globalPriorHigh", "regionPriorHigh", "massifPriorHigh", "canyonPriorHigh"),
        defaultRegionKey = "__UNKNOWN_REGION__",
        defaultMassifKey = "__UNKNOWN_MASSIF__",
        defaults = defaults,
        global = global,
        regions = regions,
        massifs = massifs,
        canyons = canyons,
    )

    private fun canyon(): Canyon = Canyon(
        id = 42,
        nom = "Riolan",
        nomComplet = "Canyon du Riolan",
        pays = "France",
        region = "Auvergne-Rhone-Alpes",
        massif = "Vercors",
        commune = "Sigale",
        cotation = "v4a4III",
        url = "/canyoning/canyon/42/riolan.html",
    )
}
