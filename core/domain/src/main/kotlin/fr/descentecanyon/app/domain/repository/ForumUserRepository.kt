package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.ForumUser

interface ForumUserRepository {

    suspend fun search(query: String, limit: Int = 50): List<ForumUser>

    suspend fun getByNormalizedUsername(username: String): ForumUser?
}
