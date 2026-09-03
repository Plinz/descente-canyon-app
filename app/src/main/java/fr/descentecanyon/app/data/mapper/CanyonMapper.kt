package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.CanyonTrackEntity
import fr.descentecanyon.app.data.local.entity.DebitEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.PhotoEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import fr.descentecanyon.app.data.local.entity.SearchIndexEntity
import fr.descentecanyon.app.data.local.entity.WatershedEntity
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonSourceType
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CanyonTrack
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.domain.model.RegulationAttachment
import fr.descentecanyon.app.domain.model.ResourceType
import fr.descentecanyon.app.domain.model.normalizeForSearch
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// --- Entity -> Domain ---

private val mapperJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class RegulationAttachmentPayload(val label: String, val url: String)

private fun List<String>.toJsonString(): String? {
    if (isEmpty()) return null
    return mapperJson.encodeToString(this)
}

private fun String?.fromJsonStringList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching { mapperJson.decodeFromString<List<String>>(this) }.getOrDefault(emptyList())
}

private fun List<RegulationAttachment>.toJsonAttachments(): String? {
    if (isEmpty()) return null
    return mapperJson.encodeToString(map { RegulationAttachmentPayload(it.label, it.url) })
}

private fun String?.fromJsonAttachments(): List<RegulationAttachment> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching {
        mapperJson.decodeFromString<List<RegulationAttachmentPayload>>(this).map {
            RegulationAttachment(it.label, it.url)
        }
    }.getOrDefault(emptyList())
}

fun CanyonEntity.toDomain(): Canyon = Canyon(
    id = id,
    nom = nom,
    nomComplet = nomComplet,
    pays = pays,
    region = region,
    departement = departement,
    commune = commune,
    communes = communesJson.fromJsonStringList(),
    massif = massif,
    bassin = bassin,
    coursEau = coursEau,
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
    interet = interet.normalizedInterest(),
    nbVotes = nbVotes,
    url = url,
    hasSpecificRegulation = hasSpecificRegulation,
    isForbidden = isForbidden,
    isOffline = isOffline,
    lastUpdated = lastUpdated,
    sourceType = sourceType.toDomainSourceType(),
    sourceKey = sourceKey.ifBlank { "dc:$id" },
)

internal fun String.normalizeCountryName(): String {
    val normalized = normalizeForSearch()
    if (normalized == "france espagne") {
        return listOf("FR", "ES")
            .map { Locale("", it).getDisplayCountry(Locale.getDefault()) }
            .filter { it.isNotBlank() }
            .joinToString(", ") { country ->
                country.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
            }
            .ifBlank { "France, Espagne" }
    }
    if (length !in 2..3) return trim()

    val code = uppercase()
    return Locale("", code).getDisplayCountry(Locale.getDefault())
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
        ?: trim()
}

fun CanyonEntity.toSummary(): CanyonSummary = CanyonSummary(
    id = id,
    nom = nom,
    pays = pays.normalizeCountryName(),
    region = region,
    departement = departement,
    cotation = cotation,
    interet = interet.normalizedInterest().takeUnless { isForbidden },
    url = url,
    isOffline = isOffline,
    isForbidden = isForbidden,
    favoriteAddedAt = favoriteAddedAt,
)

fun CanyonEntity.toSearchItem(
    representativeLat: Double? = null,
    representativeLng: Double? = null,
): CanyonSearchItem {
    val normalizedCountry = pays.normalizeCountryName()
    val countryTokens = normalizedCountry.toAdministrativeTokens()
    val departmentTokens = departement.toAdministrativeTokens()
    return CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nomComplet,
        pays = normalizedCountry,
        countryTokens = countryTokens,
        region = region,
        departement = departement,
        departmentTokens = departmentTokens,
        commune = commune.takeIf(String::isNotBlank),
        massif = massif,
        bassin = bassin,
        coursEau = coursEau,
        cotation = cotation,
        cotationRating = CotationRating.parse(cotation),
        interet = interet.normalizedInterest(),
        nbVotes = nbVotes,
        altitudeDepart = altitudeDepart,
        denivele = denivele,
        longueur = longueur,
        cascadeMax = cascadeMax,
        cordeMin = cordeMin,
        hasSpecificRegulation = hasSpecificRegulation,
        isForbidden = isForbidden,
        hasNavette = navette.hasUsefulNavette(),
        isFavorite = isFavorite,
        representativeLat = representativeLat,
        representativeLng = representativeLng,
        url = url,
        searchableText = buildList {
            add(nom)
            add(nomComplet)
            add(normalizedCountry)
            addAll(countryTokens)
            departement?.let(::add)
            addAll(departmentTokens)
            region?.let(::add)
            commune.takeIf(String::isNotBlank)?.let(::add)
            massif?.let(::add)
            bassin?.let(::add)
            coursEau?.let(::add)
        }.joinToString(" ").normalizeForSearch(),
        normalizedNom = nom.normalizeForSearch(),
        normalizedNomComplet = nomComplet.normalizeForSearch(),
    )
}

fun CanyonSearchItem.toSearchIndexEntity(): SearchIndexEntity {
    return SearchIndexEntity(
        id = id,
        nom = nom,
        nomComplet = nomComplet,
        pays = pays,
        countryTokensJson = countryTokens.toJsonString(),
        region = region,
        departement = departement,
        departmentTokensJson = departmentTokens.toJsonString(),
        subdivisionsByCountryJson = subdivisionsByCountry.toJsonString(),
        commune = commune,
        massif = massif,
        bassin = bassin,
        coursEau = coursEau,
        cotation = cotation,
        cotationVertical = cotationRating.vertical,
        cotationAquatic = cotationRating.aquatic,
        cotationEngagement = cotationRating.engagement,
        interet = interet,
        nbVotes = nbVotes,
        altitudeDepart = altitudeDepart,
        denivele = denivele,
        longueur = longueur,
        cascadeMax = cascadeMax,
        cordeMin = cordeMin,
        hasSpecificRegulation = hasSpecificRegulation,
        isForbidden = isForbidden,
        hasNavette = hasNavette,
        isFavorite = isFavorite,
        representativeLat = representativeLat,
        representativeLng = representativeLng,
        url = url,
        searchableText = searchableText,
        normalizedNom = normalizedNom,
        normalizedNomComplet = normalizedNomComplet,
    )
}

fun SearchIndexEntity.toSearchItem(): CanyonSearchItem {
    return CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nomComplet,
        pays = pays,
        countryTokens = countryTokensJson.fromJsonStringList(),
        region = region,
        departement = departement,
        departmentTokens = departmentTokensJson.fromJsonStringList(),
        subdivisionsByCountry = subdivisionsByCountryJson.fromJsonStringMap(),
        commune = commune,
        massif = massif,
        bassin = bassin,
        coursEau = coursEau,
        cotation = cotation,
        cotationRating = CotationRating(
            raw = cotation,
            vertical = cotationVertical,
            aquatic = cotationAquatic,
            engagement = cotationEngagement,
        ),
        interet = interet,
        nbVotes = nbVotes,
        altitudeDepart = altitudeDepart,
        denivele = denivele,
        longueur = longueur,
        cascadeMax = cascadeMax,
        cordeMin = cordeMin,
        hasSpecificRegulation = hasSpecificRegulation,
        isForbidden = isForbidden,
        hasNavette = hasNavette,
        isFavorite = isFavorite,
        representativeLat = representativeLat,
        representativeLng = representativeLng,
        url = url,
        searchableText = searchableText,
        normalizedNom = normalizedNom,
        normalizedNomComplet = normalizedNomComplet,
    )
}

fun List<CanyonSearchItem>.withInferredSubdivisionsByCountry(): List<CanyonSearchItem> {
    val knownCountryBySubdivision = asSequence()
        .filter { it.countryTokens.size == 1 }
        .flatMap { item ->
            item.departmentTokens.asSequence().map { subdivision ->
                subdivision.normalizeForSearch() to item.countryTokens.first()
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (subdivision, countries) ->
            countries.distinct().singleOrNull()?.let { subdivision to it }
        }
        .toMap()

    return map { item ->
        item.copy(
            subdivisionsByCountry = item.buildSubdivisionsByCountry(knownCountryBySubdivision),
        )
    }
}

private fun Map<String, List<String>>.toJsonString(): String? {
    if (isEmpty()) return null
    return mapperJson.encodeToString(this)
}

private fun String?.fromJsonStringMap(): Map<String, List<String>> {
    if (this.isNullOrBlank()) return emptyMap()
    return runCatching { mapperJson.decodeFromString<Map<String, List<String>>>(this) }.getOrDefault(emptyMap())
}

private fun CanyonSearchItem.buildSubdivisionsByCountry(
    knownCountryBySubdivision: Map<String, String>,
): Map<String, List<String>> {
    val countries = countryTokens.distinct()
    if (countries.isEmpty()) return emptyMap()

    val mapping = countries.associateWith { mutableListOf<String>() }.toMutableMap()
    val subdivisions = departmentTokens.distinct()
    if (subdivisions.isEmpty()) {
        return mapping.mapValues { emptyList() }
    }

    if (countries.size == 1) {
        mapping[countries.first()]?.addAll(subdivisions)
        return mapping.mapValues { (_, values) -> values.distinct() }
    }

    val unresolved = mutableListOf<String>()
    subdivisions.forEach { subdivision ->
        val inferredCountry = knownCountryBySubdivision[subdivision.normalizeForSearch()]
        val matchedCountry = countries.firstOrNull { it.equals(inferredCountry, ignoreCase = true) }
        if (matchedCountry != null) {
            mapping.getValue(matchedCountry).add(subdivision)
        } else {
            unresolved += subdivision
        }
    }

    val emptyCountries = countries.filter { mapping.getValue(it).isEmpty() }
    when {
        unresolved.isNotEmpty() && emptyCountries.size == 1 -> {
            mapping.getValue(emptyCountries.first()).addAll(unresolved)
        }

        unresolved.size == emptyCountries.size -> {
            unresolved.zip(emptyCountries).forEach { (subdivision, country) ->
                mapping.getValue(country).add(subdivision)
            }
        }
    }

    return mapping.mapValues { (_, values) -> values.distinct() }
}

fun CanyonEntity.toDetail(
    geoPoints: List<GeoPointEntity>,
    bibliography: List<BibliographyEntryEntity>,
    regulations: List<RegulationTextEntity>,
    tracks: List<CanyonTrackEntity>,
    photos: List<PhotoEntity>,
    debits: List<DebitEntity>,
    watershed: CanyonWatershed?,
): CanyonDetail = CanyonDetail(
    canyon = toDomain(),
    accesAval = accesAval,
    accesAmont = accesAmont,
    approche = approche,
    descente = descente,
    retour = retour,
    engagement = engagement,
    periode = periode,
    geologie = geologie,
    historique = historique,
    remarques = remarques,
    geoPoints = geoPoints.map { it.toDomain() },
    bibliography = bibliography.map { it.toDomain() },
    regulations = regulations.map { it.toDomain() },
    tracks = tracks.map { it.toDomain() },
    photos = photos.map { it.toDomain() },
    debits = debits.map { it.toDomain() },
    watershed = watershed,
)

fun CanyonTrackEntity.toDomain(): CanyonTrack = CanyonTrack(
    id = trackId,
    name = name,
    role = role,
    isPrimary = isPrimary,
    sourceFile = sourceFile,
    pointCount = pointCount,
    geometryJson = geometryJson,
    bounds = listOfNotNull(
        bboxMinLongitude,
        bboxMinLatitude,
        bboxMaxLongitude,
        bboxMaxLatitude,
    ).takeIf { it.size == 4 }?.let {
        GeoBounds(
            minLongitude = it[0],
            minLatitude = it[1],
            maxLongitude = it[2],
            maxLatitude = it[3],
        )
    },
)

fun WatershedEntity.toDomain(): CanyonWatershed = CanyonWatershed(
    areaKm2 = areaKm2,
    geometryJson = geometryJson,
    bounds = listOfNotNull(
        bboxMinLongitude,
        bboxMinLatitude,
        bboxMaxLongitude,
        bboxMaxLatitude,
    ).takeIf { it.size == 4 }?.let {
        GeoBounds(
            minLongitude = it[0],
            minLatitude = it[1],
            maxLongitude = it[2],
            maxLatitude = it[3],
        )
    },
)

fun BibliographyEntryEntity.toDomain(): BibliographyEntry = BibliographyEntry(
    id = id,
    kind = runCatching { BibliographyKind.valueOf(kind) }.getOrDefault(BibliographyKind.RESOURCE),
    resourceType = resourceType?.let { runCatching { ResourceType.valueOf(it) }.getOrNull() },
    title = title,
    authors = authorsJson.fromJsonStringList(),
    publicationYear = publicationYear,
    reference = reference,
    editor = editor,
    status = status,
    scale = scale,
    detailUrl = detailUrl,
    url = url,
)

fun RegulationTextEntity.toDomain(): Regulation = Regulation(
    id = id,
    status = status,
    action = action,
    title = title,
    summary = summary,
    remark = remark,
    details = details,
    effectiveDate = effectiveDate,
    textUrl = textUrl,
    attachments = attachmentsJson.fromJsonAttachments(),
)

fun GeoPointEntity.toDomain(): GeoPoint = GeoPoint(
    id = id,
    canyonId = canyonId,
    type = try { GeoPointType.valueOf(type) } catch (_: Exception) { GeoPointType.UNKNOWN },
    latitude = latitude,
    longitude = longitude,
    title = title,
    remark = remark,
)

fun DebitEntity.toDomain(): Debit = Debit(
    id = id,
    canyonId = canyonId,
    canyonNom = null,
    date = DateParser.parseToLocalDate(date) ?: LocalDate.of(1970, 1, 1),
    niveau = try { NiveauDebit.valueOf(niveau) } catch (_: Exception) { NiveauDebit.INCONNU },
    auteur = auteur,
    isDescended = isDescended,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    commentaire = commentaire,
)

fun ScrapedDebit.toDomain(): Debit = Debit(
    canyonId = canyonId,
    canyonNom = canyonNom.ifBlank { null },
    date = DateParser.parseToLocalDate(date) ?: LocalDate.of(1970, 1, 1),
    niveau = try { NiveauDebit.valueOf(niveauRaw) } catch (_: Exception) { NiveauDebit.INCONNU },
    auteur = auteur,
    isDescended = isDescended,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    commentaire = commentaire,
)

fun PhotoEntity.toDomain(): CanyonPhoto = CanyonPhoto(
    id = id,
    canyonId = canyonId,
    url = url,
    thumbnailUrl = thumbnailUrl,
    auteur = auteur,
    description = description,
    localPath = localPath,
)

// --- Scraped DTO -> Entity ---

fun ScrapedCanyonDetail.toEntity(): CanyonEntity = CanyonEntity(
    id = id,
    nom = nom,
    nomComplet = nomComplet,
    pays = pays,
    region = region,
    departement = departement,
    commune = commune,
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
    accesAval = accesAval,
    accesAmont = accesAmont,
    approche = approche,
    descente = descente,
    retour = retour,
    engagement = engagement,
    periode = periode,
    sourceType = CanyonSourceType.DESCENTE_CANYON.name,
    sourceKey = "dc:$id",
)

fun ScrapedGeoPoint.toEntity(canyonId: Int): GeoPointEntity = GeoPointEntity(
    canyonId = canyonId,
    type = type,
    latitude = latitude,
    longitude = longitude,
    title = title,
    remark = remark,
)

fun ScrapedDebit.toEntity(): DebitEntity = DebitEntity(
    canyonId = canyonId,
    date = DateParser.parseToIsoString(date) ?: date,
    niveau = niveauRaw,
    auteur = auteur,
    isDescended = isDescended,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    commentaire = commentaire,
)

fun ScrapedPhoto.toEntity(): PhotoEntity = PhotoEntity(
    canyonId = canyonId,
    url = url,
    thumbnailUrl = thumbnailUrl,
    auteur = auteur,
    description = description,
)

fun ScrapedCanyonSummary.toEntity(): CanyonEntity = CanyonEntity(
    id = id,
    nom = nom,
    nomComplet = nom,
    pays = pays,
    departement = departement,
    commune = "",
    cotation = cotation,
    url = url,
    sourceType = CanyonSourceType.DESCENTE_CANYON.name,
    sourceKey = "dc:$id",
)

private fun String.toDomainSourceType(): CanyonSourceType {
    return runCatching { CanyonSourceType.valueOf(this) }
        .getOrDefault(CanyonSourceType.DESCENTE_CANYON)
}

private fun Float?.normalizedInterest(): Float? = this?.takeIf { it >= 0f }?.coerceAtMost(4f)

private fun String?.hasUsefulNavette(): Boolean {
    val normalized = this?.normalizeForSearch().orEmpty()
    if (normalized.isBlank()) return false
    return normalized !in setOf("non", "no", "aucune", "aucun", "0", "-")
}

private fun String?.toAdministrativeTokens(): List<String> {
    return this.orEmpty()
        .split(',', ';')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
}
