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
)

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
data class BackendErrorDto(val error: String)

@Serializable
data class VoteRequestDto(val value: Int)
