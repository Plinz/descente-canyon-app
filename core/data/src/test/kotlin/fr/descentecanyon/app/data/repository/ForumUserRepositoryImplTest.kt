package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.catalog.CatalogDatabase
import fr.descentecanyon.app.domain.model.MemberDebitReport
import fr.descentecanyon.app.testutil.TestDatabases
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForumUserRepositoryImplTest {

    private lateinit var catalog: CatalogDatabase
    private lateinit var repository: ForumUserRepositoryImpl

    @Before
    fun setUp() {
        catalog = TestDatabases.catalog()
        repository = ForumUserRepositoryImpl(catalog.forumUserDao())
        member("Lolo_", "lolo", debitReports = 3)
        member("lolo", "lolo", debitReports = 1583, forumPosts = 5194, forumUserId = 40)
        member("Xlolox", "xlolox", debitReports = 900)
        member("Le Lolo", "le lolo", debitReports = 10)
        member("lolotte", "lolotte", debitReports = 2)
        member(
            "Max38",
            "max38",
            debitReports = 2247,
            lastReportDate = "2026-06-21",
            lastReportCanyonId = 2422,
            lastReportCanyonName = "Riolan",
        )
    }

    @After
    fun tearDown() {
        catalog.close()
    }

    @Test
    fun `the exact name comes first, then the names starting with it, then a word, then the rest`() = runTest {
        val names = repository.search("Lolo").map { it.username }

        // "lolo" and "Lolo_" are two accounts with the same search form: the more active first.
        assertEquals(listOf("lolo", "Lolo_", "lolotte", "Le Lolo", "Xlolox"), names)
    }

    @Test
    fun `a member is read by the exact account name`() = runTest {
        val member = repository.getByUsername("Max38")

        assertEquals(2247, member?.debitReportCount)
        assertEquals(MemberDebitReport(LocalDate.of(2026, 6, 21), 2422, "Riolan"), member?.lastDebitReport)
        assertNull(repository.getByUsername("max38"))
    }

    @Test
    fun `only the members among report authors are found, not the names typed without an account`() = runTest {
        val members = repository.findMembers(listOf("lolo", "Pierre, Paul et Jacques", "Max38", "", "lolo"))

        assertEquals(setOf("lolo", "Max38"), members)
    }

    @Test
    fun `the forum posts link searches by id when known, by name otherwise, and only for members who posted`() = runTest {
        member("suze51", "suze51", forumPosts = 28)

        assertEquals(
            "https://www.descente-canyon.com/forums/search.php?author_id=40&sr=posts&sk=t&sd=d",
            repository.getByUsername("lolo")?.forumPostsUrl,
        )
        assertEquals(
            "https://www.descente-canyon.com/forums/search.php?author=suze51&sr=posts&sk=t&sd=d",
            repository.getByUsername("suze51")?.forumPostsUrl,
        )
        assertNull(repository.getByUsername("Max38")?.forumPostsUrl)
    }

    private fun member(
        username: String,
        normalizedUsername: String,
        debitReports: Int = 0,
        forumPosts: Int = 0,
        forumUserId: Int? = null,
        lastReportDate: String? = null,
        lastReportCanyonId: Int? = null,
        lastReportCanyonName: String? = null,
    ) {
        catalog.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO forum_users (username, normalizedUsername, forumUserId, debitReportCount, forumPostCount, photoCount,
                canyonSheetContributionCount, lastDebitReportDate, lastDebitReportCanyonId, lastDebitReportCanyonName, updatedAt)
            VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?, '2026-10-19')
            """.trimIndent(),
            arrayOf<Any?>(
                username,
                normalizedUsername,
                forumUserId,
                debitReports,
                forumPosts,
                lastReportDate,
                lastReportCanyonId,
                lastReportCanyonName,
            ),
        )
    }
}
