package fr.descentecanyon.app.data.remote.backend

import kotlinx.serialization.Serializable

/** `v1/manifest.json`: the base64 payload and its ECDSA P-256 signature (see server/dcserver/manifest.py). */
@Serializable
data class ManifestEnvelopeDto(val format: Int, val keyId: String? = null, val payload: String, val signature: String)

@Serializable
data class ManifestPayloadDto(
    val schema: Int,
    val sequence: Long,
    val generatedAt: String? = null,
    val minVersionCode: Int = 0,
    val features: Map<String, Boolean> = emptyMap(),
    val messages: List<ManifestMessageDto> = emptyList(),
    val catalogs: List<ManifestCatalogDto> = emptyList(),
    val catalogIndex: ManifestFileDto? = null,
    val predictions: ManifestFileDto? = null,
)

/** A published file of the manifest (catalog index, predictions): its path under v1/ and its SHA-256. */
@Serializable
data class ManifestFileDto(
    val path: String,
    val sha256: String,
    val size: Long,
    val dataVersion: String? = null,
    val schemaVersion: Int? = null,
    val identityHash: String? = null,
)

/** The catalog index (scripts/app/catalog_bundles.py, server catalog job): hash and reading time of every canyon. */
@Serializable
data class CatalogIndexDto(
    val bundleSchema: Int,
    val schemaVersion: Int,
    val identityHash: String,
    val dataVersion: String,
    val bundlePath: String,
    /** Canyon id -> [hash, reading time in ms]. */
    val canyons: Map<String, List<kotlinx.serialization.json.JsonPrimitive>>,
    val shared: Map<String, SharedTableDto> = emptyMap(),
)

@Serializable
data class SharedTableDto(val hash: String, val path: String)

@Serializable
data class ManifestMessageDto(
    val id: String,
    val level: String,
    val minVersionCode: Int = 0,
    val maxVersionCode: Int? = null,
    val until: String? = null,
    val text: Map<String, String> = emptyMap(),
)

@Serializable
data class ManifestCatalogDto(
    val schemaVersion: Int,
    val identityHash: String,
    val dataVersion: String,
    val path: String,
    val sha256: String,
    val size: Long,
    val uncompressedSize: Long,
    val canyonCount: Int,
)

@Serializable
data class TrackListDto(val canyonId: Int, val tracks: List<TrackDto> = emptyList())

@Serializable
data class TrackDto(
    val id: String,
    val canyonId: Int,
    val role: String,
    val createdAt: String,
    val recordedMonth: String? = null,
    val lengthM: Int,
    val ascentM: Int? = null,
    val descentM: Int? = null,
    val pointCount: Int = 0,
    /** [min longitude, min latitude, max longitude, max latitude]. */
    val bbox: List<Double>,
    /** Segments of [longitude, latitude(, elevation)] coordinates. */
    val geometry: List<List<List<Double>>>,
    val up: Int = 0,
    val down: Int = 0,
    val standing: String,
    val score: Double = 0.0,
    val isReference: Boolean = false,
)

@Serializable
data class BackendErrorDto(val error: String, val field: String? = null)

/** A sheet proposed to the server: the fields of a sheet of the site, blank ones left out. */
@Serializable
data class CanyonSheetDto(
    val nom: String? = null,
    val nomComplet: String? = null,
    val pays: String? = null,
    val region: String? = null,
    val departement: String? = null,
    val commune: String? = null,
    val massif: String? = null,
    val bassin: String? = null,
    val coursEau: String? = null,
    val cotation: String? = null,
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    val tempsApproche: String? = null,
    val tempsDescente: String? = null,
    val tempsRetour: String? = null,
    val navette: String? = null,
    val accesAval: String? = null,
    val accesAmont: String? = null,
    val approche: String? = null,
    val descente: String? = null,
    val retour: String? = null,
    val engagement: String? = null,
    val periode: String? = null,
    val geologie: String? = null,
    val historique: String? = null,
    val remarques: String? = null,
    val isForbidden: Boolean? = null,
    val points: List<SheetPointDto> = emptyList(),
)

@Serializable
data class SheetPointDto(val type: String, val latitude: Double, val longitude: Double, val title: String? = null)

/** No default on [kind] nor [licence]: the shared Json leaves default values out of what it writes. */
@Serializable
data class ProposalRequestDto(val kind: String, val canyonId: Int? = null, val content: CanyonSheetDto, val licence: String)

@Serializable
data class ProposalDto(
    val id: String,
    val kind: String,
    val canyonId: Int? = null,
    val name: String = "",
    val status: String,
    val createdAt: String,
    val decidedAt: String? = null,
    val reason: String? = null,
    val note: String? = null,
)

@Serializable
data class ProposalListDto(val proposals: List<ProposalDto> = emptyList())

/** No default on [kind] nor [text]: the shared Json leaves default values out of what it writes. */
@Serializable
data class FeedbackRequestDto(
    val kind: String,
    val text: String,
    val technical: FeedbackTechnicalDto? = null,
    val contact: FeedbackContactDto? = null,
)

@Serializable
data class FeedbackTechnicalDto(
    val appVersion: String,
    val versionCode: Long,
    val android: String,
    val device: String,
    val language: String,
)

@Serializable
data class FeedbackContactDto(val username: String, val email: String? = null)

@Serializable
data class FeedbackDto(
    val id: String,
    val kind: String,
    val text: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String? = null,
    val reply: String? = null,
    val repliedAt: String? = null,
)

@Serializable
data class FeedbackListDto(val feedback: List<FeedbackDto> = emptyList())

@Serializable
data class VoteRequestDto(val value: Int)

/**
 * The debit estimates of every canyon computed by the server (server/dcserver/predictions/job.py),
 * gzipped JSON referenced by the manifest's `predictions` entry.
 */
@Serializable
data class ServerPredictionsDto(
    val schema: Int,
    val generatedAt: String? = null,
    /** Epoch second of the run. */
    val computedAt: Long,
    val modelVersion: String? = null,
    val policy: String = "BALANCED",
    val ordinalCutpoints: List<Double> = emptyList(),
    val liveCheck: LiveCheckDto? = null,
    val canyons: Map<String, ServerCanyonPredictionDto> = emptyMap(),
)

@Serializable
data class ServerCanyonPredictionDto(
    val tz: String,
    /** WeatherLocationSource name. */
    val src: String,
    val lat: Double,
    val lon: Double,
    /** RuntimeLookupSource name. */
    val lookup: String,
    /** Epoch second of the forecast used. */
    val wx: Long,
    /** Epoch day of the last report the server knew before today, null without any. */
    val last: Long? = null,
    /** J0 feature values shown with the estimate (feature_spec.json names). */
    val basis: Map<String, Double?> = emptyMap(),
    /** `[date, horizonDays, [p SEC..CRUE], rank, displayScore, highThreshold]` per day. */
    val days: List<kotlinx.serialization.json.JsonArray> = emptyList(),
)

@Serializable
data class LiveCheckDto(val days: Int, val since: String, val byHorizon: List<LiveCheckHorizonDto> = emptyList())

@Serializable
data class LiveCheckHorizonDto(
    val horizon: Int,
    val reports: Int = 0,
    val exact: Int = 0,
    val withinOne: Int = 0,
    val highReports: Int = 0,
    val highCaught: Int = 0,
    val highShown: Int = 0,
    val highShownRight: Int = 0,
)
