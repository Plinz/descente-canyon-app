package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/**
 * A member of descente-canyon.com: the site account, which is also the forum one. The counters are
 * the site's, as shown under the member's reports. Names typed without an account under a debit
 * report ("Pierre, Paul et Jacques") are not members.
 */
data class ForumUser(
    /** The account name exactly as the site shows it; it identifies the member. */
    val username: String,
    val forumUserId: Int? = null,
    val debitReportCount: Int = 0,
    val forumPostCount: Int = 0,
    /** Null when unknown: the site shows its counters only under the member's report remarks. */
    val photoCount: Int? = null,
    val canyonSheetContributionCount: Int? = null,
    val lastDebitReport: MemberDebitReport? = null,
    val lastForumPostDate: LocalDate? = null,
    val lastForumPostUrl: String? = null,
    /** The forum search of the member's posts, which works without an account (profiles do not). */
    val forumPostsUrl: String? = null,
)

data class MemberDebitReport(val date: LocalDate, val canyonId: Int?, val canyonName: String?)
