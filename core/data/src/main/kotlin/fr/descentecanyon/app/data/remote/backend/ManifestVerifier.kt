package fr.descentecanyon.app.data.remote.backend

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Checks the manifest signature against the public keys built into the app before reading it. A
 * manifest that fails is ignored as a whole: the server, or anyone between it and the phone, cannot
 * make the app download anything else than what the key holder published.
 */
class ManifestVerifier(publicKeys: List<String>, private val json: Json) {

    private val keys: List<PublicKey> = publicKeys.mapNotNull { encoded ->
        runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())))
        }.getOrNull()
    }

    /** Returns the payload of a correctly signed manifest of a supported schema; fails otherwise. */
    fun verify(envelopeText: String): ManifestPayloadDto {
        val envelope = decode { json.decodeFromString(ManifestEnvelopeDto.serializer(), envelopeText) }
        if (envelope.format != ENVELOPE_FORMAT) appError(AppError.UnexpectedResponse)
        val payload = decode { Base64.getDecoder().decode(envelope.payload) }
        val signature = decode { Base64.getDecoder().decode(envelope.signature) }
        if (keys.none { key -> verifies(key, payload, signature) }) {
            appError(AppError.UnexpectedResponse, GeneralSecurityException("Manifest signature does not verify"))
        }
        val manifest = decode { json.decodeFromString(ManifestPayloadDto.serializer(), payload.decodeToString()) }
        if (manifest.schema != PAYLOAD_SCHEMA) appError(AppError.UnexpectedResponse)
        return manifest
    }

    private fun verifies(key: PublicKey, payload: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (exception: SerializationException) {
        appError(AppError.UnexpectedResponse, exception)
    } catch (exception: IllegalArgumentException) {
        appError(AppError.UnexpectedResponse, exception)
    }

    private companion object {
        const val ENVELOPE_FORMAT = 1
        const val PAYLOAD_SCHEMA = 1
    }
}
