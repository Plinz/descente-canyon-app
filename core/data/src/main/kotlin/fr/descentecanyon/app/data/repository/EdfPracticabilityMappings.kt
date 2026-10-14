package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.EdfPracticabilityReference

internal object EdfPracticabilityMappings {
    private val references = mapOf(
        21002 to 40746706L,
        22552 to 40746703L,
        2720 to 40746705L,
        2724 to 40746705L,
        2737 to 40746704L,
        21063 to 40746704L,
    )

    fun getReference(canyonId: Int): EdfPracticabilityReference? {
        val practicabilityId = references[canyonId] ?: return null
        return EdfPracticabilityReference(
            practicabilityId = practicabilityId,
            sourceUrl = "https://mariviereetmoi.edf.fr/#/map/place/PRACTICABILITY/$practicabilityId",
        )
    }
}
