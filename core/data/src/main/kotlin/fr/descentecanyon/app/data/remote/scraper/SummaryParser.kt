package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import org.jsoup.nodes.Document

/**
 * Parses a canyon summary page: /canyoning/canyon/{id}/{slug}.html
 */
internal object SummaryParser {

    fun parse(doc: Document, canyonId: Int): ScrapedCanyonDetail {
        val h1 = doc.selectFirst("h1")
        val nom = h1?.selectFirst("strong")?.text()?.trim() ?: ""
        val nomComplet = doc.selectFirst("h2.h3")?.text()?.trim()
            ?: h1?.text()?.trim() ?: ""
        val commune = h1?.selectFirst("small")?.text()?.trim() ?: ""

        // Location from breadcrumb: Accueil > Base > Country > Dept > Canyon
        val breadcrumbs = doc.select("ol.breadcrumb li")
        val pays = if (breadcrumbs.size > 2) breadcrumbs[2].text().trim() else ""
        val departement = if (breadcrumbs.size > 3) breadcrumbs[3].text().trim() else null

        // Fiche technique section
        val ficheSection = doc.selectFirst("div.fichetechnique")
            ?: doc.selectFirst("div.row") // fallback

        // Location details from Situation paragraph
        val region = ficheSection?.select("a[href*=/lieu/]")
            ?.firstOrNull { it.attr("href").contains(Regex("/lieu/\\d{5}/")) }
            ?.text()?.trim()

        val massif = ficheSection?.select("a[href*=/lieu/14/], a[href*=/lieu/15/], a[href*=/lieu/16/]")
            ?.firstOrNull()?.text()?.trim()

        // Technical data from badge spans
        val badges = ficheSection ?: doc

        val cotation = badges.badgeText("picto-huit") ?: ""
        val altitudeDepart = badges.badgeText("picto-altidep")?.extractInt()
        val denivele = badges.badgeText("picto-deniv")?.extractInt()
        val longueur = badges.badgeText("picto-long")?.extractInt()
        val cascadeMax = badges.badgeText("picto-cmax")?.extractInt()
        val cordeMin = badges.badgeText("picto-corde")?.extractInt()
        val tempsApproche = badges.badgeText("picto-appr")
        val tempsDescente = badges.badgeText("picto-desc")
        val tempsRetour = badges.badgeText("picto-retour")
        val navette = badges.badgeText("picto-navette")

        // Interest rating
        val interestText = ficheSection?.selectFirst("strong")?.text()?.trim()
        val interet = interestText?.split("/")?.firstOrNull()?.extractFloat()

        // Nb votes: "(27 votes)"
        val votesText = ficheSection?.selectFirst("a[href*=canyon-interet]")?.text() ?: ""
        val nbVotes = Regex("(\\d+)\\s*vote").find(votesText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val url = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?: SiteUrls.canyon(canyonId)

        return ScrapedCanyonDetail(
            id = canyonId,
            nom = nom,
            nomComplet = nomComplet,
            pays = pays,
            region = region,
            departement = departement,
            commune = commune.removeSurrounding("(", ")").trim().let {
                // "La Burbanche (Ain)" -> extract commune before parenthesis
                it.substringBefore("(").trim().ifEmpty { it }
            },
            massif = massif,
            cotation = cotation,
            altitudeDepart = altitudeDepart,
            denivele = denivele,
            longueur = longueur,
            cascadeMax = cascadeMax,
            cordeMin = cordeMin,
            tempsApproche = tempsApproche,
            tempsDescente = tempsDescente,
            tempsRetour = tempsRetour,
            navette = navette,
            interet = interet,
            nbVotes = nbVotes,
            url = url,
        )
    }
}
