package fr.descentecanyon.app.data.remote.backend

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The manifest fixture is signed by the Python server (server/tools/make_app_fixtures.py). */
class ManifestVerifierTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val envelope = resource("backend/manifest-envelope.json")
    private val publicKey = resource("backend/manifest-public-key.txt").trim()

    @Test
    fun `a manifest signed by the server verifies and reads`() {
        val payload = ManifestVerifier(listOf(publicKey), json).verify(envelope)

        assertEquals(1, payload.schema)
        assertEquals(1L, payload.sequence)
        assertEquals(50, payload.minVersionCode)
        assertEquals(false, payload.features["communityTracks"])
        assertEquals("site-2026-12", payload.messages.single().id)
        assertEquals("warning", payload.messages.single().level)
        assertEquals("The site is under maintenance tonight.", payload.messages.single().text["en"])
        val catalog = payload.catalogs.single()
        assertEquals(4, catalog.schemaVersion)
        assertEquals("38ceb224a4367c7742d73e390e93467c", catalog.identityHash)
        assertEquals(4127, catalog.canyonCount)
    }

    @Test
    fun `any of several keys may have signed it`() {
        val otherKey = Base64.getEncoder().encodeToString(newKeyPair().public.encoded)

        ManifestVerifier(listOf(otherKey, publicKey), json).verify(envelope)
    }

    @Test
    fun `a payload changed after signing is refused`() {
        val fields = json.parseToJsonElement(envelope).jsonObject
        val payload = Base64.getDecoder().decode((fields.getValue("payload") as JsonPrimitive).content).decodeToString()
        val tampered = payload.replace("\"minVersionCode\":50", "\"minVersionCode\":0")
        val forged = JsonObject(
            fields + ("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(tampered.toByteArray()))),
        ).toString()

        assertRefused { ManifestVerifier(listOf(publicKey), json).verify(forged) }
    }

    @Test
    fun `a manifest signed with another key is refused`() {
        assertRefused { ManifestVerifier(listOf(Base64.getEncoder().encodeToString(newKeyPair().public.encoded)), json).verify(envelope) }
    }

    @Test
    fun `an unknown schema or a broken envelope is refused`() {
        val pair = newKeyPair()
        val key = Base64.getEncoder().encodeToString(pair.public.encoded)
        val payload = """{"schema":2,"sequence":3}""".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
        val future = """{"format":1,"payload":"${Base64.getEncoder().encodeToString(
            payload,
        )}","signature":"${Base64.getEncoder().encodeToString(signature)}"}"""

        assertRefused { ManifestVerifier(listOf(key), json).verify(future) }
        assertRefused { ManifestVerifier(listOf(key), json).verify("<html>maintenance</html>") }
        assertRefused { ManifestVerifier(emptyList(), json).verify(envelope) }
    }

    private fun assertRefused(block: () -> Unit) {
        val error = assertThrows(AppException::class.java) { block() }
        assertEquals(AppError.UnexpectedResponse, error.error)
    }

    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path)) { path }.readText()
}
