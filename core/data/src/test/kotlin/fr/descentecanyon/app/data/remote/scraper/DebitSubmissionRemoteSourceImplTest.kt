package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.auth.SessionState
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitDelivery
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.InMemoryCredentialStore
import fr.descentecanyon.app.testutil.SitePages
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebitSubmissionRemoteSourceImplTest {

    private val canyonId = 2186
    private val formUrl = SiteUrls.debitForm(canyonId)
    private val observationsUrl = SiteUrls.canyonDebits(canyonId)
    private val siteClient = FakeSiteHttpClient()
    private val store = InMemoryCredentialStore()
    private val sessionManager = SessionManager(siteClient, SiteCookieJar(), store)
    private val remoteSource = DebitSubmissionRemoteSourceImpl(siteClient, sessionManager)

    @Test
    fun `posts the form fields and is confirmed by the redirect to the observations`() = runTest {
        loggedIn("Antoine")
        siteClient.onGet(formUrl, SitePages.loggedIn("Antoine", connectedForm()))
        siteClient.onPost(formUrl, SitePages.loggedIn("Antoine"), finalUrl = observationsUrl)

        val result = remoteSource.submit(submission(observerName = "Antoine", observerEmail = null))

        assertEquals(DebitDelivery.CONFIRMED, result.getOrThrow())
        val post = siteClient.posts.single()
        assertEquals(formUrl, post.referer)
        assertEquals(SiteUrls.BASE_URL, post.origin)
        assertFalse(post.fields.containsKey("groupe"))
        assertEquals("2026-05-11", post.fields["date_mesure"])
        assertEquals("1", post.fields["parcouru"])
        assertEquals("4", post.fields["debit"])
        assertEquals("3", post.fields["eau"])
        assertEquals("3", post.fields["air"])
        assertEquals("RAS", post.fields["remarque"])
        assertEquals("Perso", post.fields["perso"])
        assertEquals("keep", post.fields["token"])
    }

    @Test
    fun `an anonymous form for a logged-in report means the session expired`() = runTest {
        loggedIn("antoine")
        siteClient.onGet(formUrl, SitePages.anonymous(anonymousForm()))

        val result = remoteSource.submit(submission(observerName = "antoine", observerEmail = null))

        assertEquals(AppError.SessionExpired, result.appErrorOrNull())
        assertEquals(SessionState.Expired("antoine"), sessionManager.state.value)
        assertTrue(siteClient.posts.isEmpty())
    }

    @Test
    fun `an anonymous report without session asks to log in`() = runTest {
        siteClient.onGet(formUrl, SitePages.anonymous(anonymousForm()))

        val result = remoteSource.submit(submission(observerName = "Antoine", observerEmail = null))

        assertEquals(AppError.AuthenticationRequired, result.appErrorOrNull())
    }

    @Test
    fun `a form shown again with an error is a rejection carrying the site message`() = runTest {
        siteClient.onGet(formUrl, SitePages.anonymous(anonymousForm()))
        siteClient.onPost(formUrl, SitePages.anonymous("""<div class="alert alert-danger">Date invalide</div>${anonymousForm()}"""))
        siteClient.onGet(observationsUrl, SitePages.anonymous())

        val result = remoteSource.submit(submission(observerName = "Antoine", observerEmail = "a@example.com"))

        assertEquals(AppError.Rejected("Date invalide"), result.appErrorOrNull())
    }

    @Test
    fun `a maintenance page after posting is a failure, never a success`() = runTest {
        siteClient.onGet(formUrl, SitePages.anonymous(anonymousForm()))
        siteClient.onPost(formUrl, SitePages.NOT_A_SITE_PAGE)

        val result = remoteSource.submit(submission(observerName = "Antoine", observerEmail = "a@example.com"))

        assertEquals(AppError.UnexpectedResponse, result.appErrorOrNull())
    }

    @Test
    fun `an unclear answer is confirmed when the report is read back`() = runTest {
        siteClient.onGet(formUrl, SitePages.anonymous(anonymousForm()))
        siteClient.onPost(formUrl, SitePages.anonymous("<p>Merci pour votre contribution</p>"))
        siteClient.onGet(observationsUrl, fixture("canyon_debits.html"))

        // The fixture holds a report by steph758 on Sunday 22 March 2026, "Petit filet d'eau".
        val result = remoteSource.submit(
            submission(
                observerName = "steph758",
                observerEmail = "steph@example.com",
                date = LocalDate.of(2026, 3, 22),
                level = NiveauDebit.FILET,
            ),
        )

        assertEquals(DebitDelivery.CONFIRMED, result.getOrThrow())
    }

    @Test
    fun `an unclear answer without the report read back stays unconfirmed`() = runTest {
        siteClient.onGet(formUrl, SitePages.anonymous(anonymousForm()))
        siteClient.onPost(formUrl, SitePages.anonymous("<p>Merci, un e-mail de confirmation vous a été envoyé</p>"))
        siteClient.onGet(observationsUrl, fixture("canyon_debits.html"))

        val result = remoteSource.submit(submission(observerName = "Antoine", observerEmail = "a@example.com"))

        assertEquals(DebitDelivery.UNCONFIRMED, result.getOrThrow())
    }

    @Test
    fun `a missing form on a regular page means the site changed`() = runTest {
        siteClient.onGet(formUrl, SitePages.anonymous("<p>Nouveau formulaire</p>"))

        val result = remoteSource.submit(submission(observerName = "Antoine", observerEmail = "a@example.com"))

        assertEquals(AppError.SiteFormatChanged, result.appErrorOrNull())
    }

    private fun loggedIn(username: String) {
        store.saveSession(username, listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=abc; Secure")!!))
        sessionManager.restoreSession()
    }

    private fun submission(
        observerName: String,
        observerEmail: String?,
        date: LocalDate = LocalDate.of(2026, 5, 11),
        level: NiveauDebit = NiveauDebit.CORRECT,
    ) = DebitSubmission(
        canyonId = canyonId,
        observerName = observerName,
        observerEmail = observerEmail,
        observationDate = date,
        observationType = ObservationType.PARCOURU,
        debitLevel = level,
        waterTemperature = WaterTemperature.FROIDE,
        airTemperature = AirTemperature.BON,
        comment = "RAS",
        personalComment = "Perso",
    )

    private fun anonymousForm() = """
        <form id="monformulaire" method="POST">
            <input name="groupe" value=""><input name="emailgroupe" value="">
            <input name="date_mesure" value="2026-05-10">
            <input type="hidden" name="parcouru" value=""><input type="hidden" name="debit" value="">
            <input type="hidden" name="eau" value=""><input type="hidden" name="air" value="">
            <textarea name="remarque"></textarea>
        </form>
    """.trimIndent()

    private fun connectedForm() = """
        <form id="monformulaire" method="POST">
            <input type="hidden" name="token" value="keep">
            <input name="date_mesure" value="2026-05-10">
            <input type="hidden" name="parcouru" value=""><input type="hidden" name="debit" value="">
            <input type="hidden" name="eau" value=""><input type="hidden" name="air" value="">
            <textarea name="remarque"></textarea><textarea name="perso"></textarea>
            <input type="submit" name="envoyer" value="Envoyer">
        </form>
    """.trimIndent()

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource("html/$name")).readText()
}
