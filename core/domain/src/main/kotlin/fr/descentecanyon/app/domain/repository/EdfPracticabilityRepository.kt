package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.EdfPracticabilityReference

interface EdfPracticabilityRepository {
    fun getReference(canyonId: Int): EdfPracticabilityReference?

    suspend fun getStatus(canyonId: Int): Result<CanyonEdfPracticability>
}
