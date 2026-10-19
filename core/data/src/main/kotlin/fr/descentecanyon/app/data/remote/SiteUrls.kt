package fr.descentecanyon.app.data.remote

import java.net.URLEncoder

/** Addresses of the descente-canyon.com pages the app reads or posts to. */
object SiteUrls {
    const val BASE_URL = "https://www.descente-canyon.com"
    const val HOME = "$BASE_URL/"
    const val LOGIN = "$BASE_URL/login"
    const val LATEST_DEBITS = "$BASE_URL/canyoning/derniers-debits"
    const val FORUM_ACTIVE_TOPICS = "$BASE_URL/forums/search.php?search_id=active_topics"
    const val FORUM_CATEGORIES = "$BASE_URL/forums/"

    /** Every flow report of the logged-in member, every year at once ("Ma liste de courses"). */
    const val MY_OBSERVATIONS = "$BASE_URL/perso/observations/all"

    fun canyon(canyonId: Int): String = "$BASE_URL/canyoning/canyon/$canyonId/"

    fun canyonDescription(canyonId: Int): String = "$BASE_URL/canyoning/canyon-description/$canyonId/topo.html"

    fun canyonMap(canyonId: Int): String = "$BASE_URL/canyoning/canyon-carte/$canyonId/carte.html"

    fun canyonPhotos(canyonId: Int): String = "$BASE_URL/canyoning/canyon-photo/$canyonId/photographie.html"

    fun canyonDebits(canyonId: Int): String = "$BASE_URL/canyoning/canyon-debit/$canyonId/observations.html"

    fun debitForm(canyonId: Int): String = "$BASE_URL/canyoning/ajout-debit/$canyonId/formulaire-observation.html"

    fun interestRating(canyonId: Int): String = "$BASE_URL/canyoning/canyon-interet/$canyonId/interet.html"

    fun forumUserPosts(authorId: Int): String = "$BASE_URL/forums/search.php?author_id=$authorId&sr=posts&sk=t&sd=d"

    /** The same search by account name: it works without being logged in, unlike the member profiles. */
    fun forumUserPostsByName(username: String): String =
        "$BASE_URL/forums/search.php?author=${URLEncoder.encode(username, "UTF-8")}&sr=posts&sk=t&sd=d"
}
