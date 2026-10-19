package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.ForumUser

/** The members of descente-canyon.com shipped with the app. */
interface ForumUserRepository {

    /** Members whose name matches [query], the closest and most active first. */
    suspend fun search(query: String, limit: Int = 50): List<ForumUser>

    suspend fun getByUsername(username: String): ForumUser?

    /** Those of [names] (debit report authors, say) that are members rather than names typed without an account. */
    suspend fun findMembers(names: Collection<String>): Set<String>
}
