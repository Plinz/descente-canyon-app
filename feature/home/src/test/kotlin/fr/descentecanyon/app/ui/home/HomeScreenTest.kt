package fr.descentecanyon.app.ui.home

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.NiveauDebit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeScreenTest {

    @Test
    fun `latest debit item key stays unique when remote ids are default`() {
        val first = Debit(
            id = 0,
            canyonId = 220,
            canyonNom = "Ruzand",
            date = LocalDate.of(2026, 3, 17),
            niveau = NiveauDebit.CORRECT,
            auteur = "Alice",
        )
        val second = Debit(
            id = 0,
            canyonId = 2186,
            canyonNom = "Valouse",
            date = LocalDate.of(2026, 3, 22),
            niveau = NiveauDebit.FILET,
            auteur = "Bob",
        )

        assertNotEquals(latestDebitItemKey(first), latestDebitItemKey(second))
    }

    @Test
    fun `forum topic item key stays unique for different updates`() {
        val first = ForumActiveTopic(
            topicId = 28125,
            title = "Baisse des notes Gamchi , Trummel IV",
            forumId = 16,
            forumName = "SUISSE",
            replyCount = 34,
            viewCount = 34624,
            lastAuthor = "Max38",
            lastPostedAtText = "ven. 03 avr. 2026 22:20",
            lastPostedAtEpochMs = 1_743_800_454_000,
            topicUrl = "topic-1",
            lastMessageUrl = "post-1",
        )
        val second = first.copy(
            lastPostedAtText = "sam. 04 avr. 2026 08:17",
            lastPostedAtEpochMs = 1_743_836_234_000,
            lastMessageUrl = "post-2",
        )

        assertNotEquals(forumTopicItemKey(first), forumTopicItemKey(second))
    }

    @Test
    fun `descente canyon canyon url targets summary page without requiring slug`() {
        assertEquals(
            "https://www.descente-canyon.com/canyoning/canyon/23332/",
            descenteCanyonCanyonUrl(23332),
        )
    }
}
