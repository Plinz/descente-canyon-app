package fr.descentecanyon.app.data.remote

/** Addresses of the descente-canyon.com pages the app reads or posts to. */
object SiteUrls {
    const val BASE_URL = "https://www.descente-canyon.com"
    const val HOME = "$BASE_URL/"
    const val LOGIN = "$BASE_URL/login"
    const val LATEST_DEBITS = "$BASE_URL/canyoning/derniers-debits"
    const val FORUM_ACTIVE_TOPICS = "$BASE_URL/forums/search.php?search_id=active_topics"
    const val FORUM_CATEGORIES = "$BASE_URL/forums/"

    fun canyon(canyonId: Int): String = "$BASE_URL/canyoning/canyon/$canyonId/"

    fun canyonDescription(canyonId: Int): String = "$BASE_URL/canyoning/canyon-description/$canyonId/topo.html"

    fun canyonMap(canyonId: Int): String = "$BASE_URL/canyoning/canyon-carte/$canyonId/carte.html"

    fun canyonPhotos(canyonId: Int): String = "$BASE_URL/canyoning/canyon-photo/$canyonId/photographie.html"

    fun canyonDebits(canyonId: Int): String = "$BASE_URL/canyoning/canyon-debit/$canyonId/observations.html"

    fun debitForm(canyonId: Int): String = "$BASE_URL/canyoning/ajout-debit/$canyonId/formulaire-observation.html"

    fun interestRating(canyonId: Int): String = "$BASE_URL/canyoning/canyon-interet/$canyonId/interet.html"

    fun forumUserPosts(authorId: Int): String = "$BASE_URL/forums/search.php?author_id=$authorId&sr=posts&sk=t&sd=d"
}
