package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.DebitRuntimeLookups
import fr.descentecanyon.app.domain.model.ResolvedRuntimeFeatureLookup
import fr.descentecanyon.app.domain.model.RuntimeLookupSource

internal object DebitRuntimeLookupResolver {

    fun resolve(canyon: Canyon, lookups: DebitRuntimeLookups): ResolvedRuntimeFeatureLookup {
        val resolvedRegionKey = canyon.region ?: lookups.defaultRegionKey
        val resolvedMassifKey = canyon.massif ?: lookups.defaultMassifKey
        val canyonEntry = lookups.canyons[canyon.id]
        val regionValues = lookups.regions[resolvedRegionKey].orEmpty()
        val massifValues = lookups.massifs[resolvedMassifKey].orEmpty()

        val featureValues = buildMap {
            putAll(lookups.defaults)
            putAll(lookups.global)
            putAll(regionValues)
            putAll(massifValues)
            if (canyonEntry != null) {
                putAll(canyonEntry.values)
            }
        }

        val lookupSource = when {
            canyonEntry != null -> RuntimeLookupSource.CANYON
            massifValues.isNotEmpty() -> RuntimeLookupSource.MASSIF
            regionValues.isNotEmpty() -> RuntimeLookupSource.REGION
            else -> RuntimeLookupSource.GLOBAL
        }

        return ResolvedRuntimeFeatureLookup(
            canyonId = canyon.id,
            regionKey = canyonEntry?.regionKey ?: resolvedRegionKey,
            massifKey = canyonEntry?.massifKey ?: resolvedMassifKey,
            lookupSource = lookupSource,
            featureValues = featureValues,
        )
    }
}
