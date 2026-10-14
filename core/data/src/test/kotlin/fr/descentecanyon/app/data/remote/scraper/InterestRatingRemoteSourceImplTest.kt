package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.auth.SessionState
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.InMemoryCredentialStore
import fr.descentecanyon.app.testutil.SitePages
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InterestRatingRemoteSourceImplTest {

    private val siteClient = FakeSiteHttpClient()
    private val store = InMemoryCredentialStore()
    private val sessionManager = SessionManager(siteClient, SiteCookieJar(), store)
    private val remoteSource = InterestRatingRemoteSourceImpl(siteClient, sessionManager)

    @Before
    fun logIn() {
        store.saveSession("Antoine", listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=abc; Secure")!!))
        sessionManager.restoreSession()
    }

    @Test
    fun `parses personal and aggregate ratings`() = runTest {
        siteClient.onGet(SiteUrls.interestRating(26), SitePages.loggedIn("Antoine", interestForm(26, personal = "2.5")))

        val rating = remoteSource.get(26).getOrThrow()

        assertEquals(2.5f, rating.personalRating)
        assertEquals(2.6f, rating.averageRating)
        assertEquals(2.7f, rating.medianRating)
        assertEquals(150, rating.voteCount)
    }

    @Test
    fun `parses aggregate ratings with spaced slash and no personal rating`() = runTest {
        siteClient.onGet(SiteUrls.interestRating(211), SitePages.loggedIn("Antoine", interestFormWithoutPersonalRating()))

        val rating = remoteSource.get(211).getOrThrow()

        assertEquals(null, rating.personalRating)
        assertEquals(3.0f, rating.averageRating)
        assertEquals(3.5f, rating.medianRating)
        assertEquals(211, rating.voteCount)
    }

    @Test
    fun `submits the dynamic submit field and succeeds when the page shows the vote`() = runTest {
        val url = SiteUrls.interestRating(26)
        siteClient.onGet(url, SitePages.loggedIn("Antoine", interestForm(26, personal = "2.5")))
        siteClient.onPost(url, SitePages.loggedIn("Antoine", interestForm(26, personal = "3.7")))

        val result = remoteSource.submit(InterestRatingSubmission(canyonId = 26, rating = 3.7f))

        assertTrue(result.isSuccess)
        val post = siteClient.posts.single()
        assertEquals("3.7", post.fields["vote"])
        assertEquals("46140", post.fields["id_interet"])
        assertEquals("enregistrer", post.fields["valid-230"])
        assertEquals(url, post.referer)
        assertEquals(SiteUrls.BASE_URL, post.origin)
    }

    @Test
    fun `reads the vote back when the answer does not show it`() = runTest {
        val url = SiteUrls.interestRating(26)
        siteClient.onGet(url, SitePages.loggedIn("Antoine", interestForm(26, personal = "2.5")))
        siteClient.onGet(url, SitePages.loggedIn("Antoine", interestForm(26, personal = "3.0")))
        siteClient.onPost(url, SitePages.loggedIn("Antoine", "<p>Merci</p>"))

        assertTrue(remoteSource.submit(InterestRatingSubmission(canyonId = 26, rating = 3.0f)).isSuccess)
    }

    @Test
    fun `fails when the vote never shows up`() = runTest {
        val url = SiteUrls.interestRating(26)
        siteClient.onGet(url, SitePages.loggedIn("Antoine", interestForm(26, personal = "2.5")))
        siteClient.onPost(url, SitePages.loggedIn("Antoine", "<p>Merci</p>"))

        val result = remoteSource.submit(InterestRatingSubmission(canyonId = 26, rating = 3.0f))

        assertEquals(AppError.UnexpectedResponse, result.appErrorOrNull())
    }

    @Test
    fun `a page without the voting form means the session expired`() = runTest {
        siteClient.onGet(SiteUrls.interestRating(26), SitePages.anonymous("<p>Veuillez vous identifier</p>"))

        val result = remoteSource.submit(InterestRatingSubmission(canyonId = 26, rating = 2.5f))

        assertEquals(AppError.SessionExpired, result.appErrorOrNull())
        assertEquals(SessionState.Expired("Antoine"), sessionManager.state.value)
        assertTrue(siteClient.posts.isEmpty())
    }

    private fun interestForm(canyonId: Int, personal: String) = """
        <p>Moyenne des notes : <b>2.6</b>/4</p>
        <p>Médiane des notes : <b>2.7</b>/4</p>
        <p><b>Répartition des 150 notes d'intéret</b>.</p>
        <form action="/canyoning/canyon-interet/$canyonId/interet.html" method="post">
            <p><b>Antoine</b>, vous avez signalé un intérêt de <b>$personal/4</b> pour ce canyon.</p>
            <input type="hidden" value="46140" name="id_interet" id="id_interet" />
            <input type="text" name="vote" size="4" maxlength="3" />
            <input type="submit" value="enregistrer" name="valid-230" />
        </form>
    """.trimIndent()

    private fun interestFormWithoutPersonalRating() = """
        <div>
            <p>
                Moyenne des notes : <img alt="intérêt" /> <b>3</b> /4<br />
                Médiane des notes : <img alt="intérêt" /> <b>3.5</b> /4 (moins sensible aux notes extrêmes)
            </p>
            <p><b>Répartition des 211 notes d'intéret</b>.</p>
        </div>
        <form action="/canyoning/canyon-interet/211/interet.html" method="post">
            <p><b>Antoine</b>, vous pouvez donner une note personnelle d'intérêt.</p>
            <input type="text" name="vote" size="4" maxlength="3" />
            <input type="submit" value="enregistrer" name="valid-230" />
        </form>
    """.trimIndent()
}
