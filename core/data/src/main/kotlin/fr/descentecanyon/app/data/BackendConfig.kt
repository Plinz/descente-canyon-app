package fr.descentecanyon.app.data

/**
 * The app's own server, as built into this version (see server/README.md). Without an HTTPS
 * address and at least one public key, the app never calls any server of its own.
 */
data class BackendConfig(
    val baseUrl: String,
    /** Base64 DER SubjectPublicKeyInfo of the ECDSA P-256 keys allowed to sign the manifest. */
    val publicKeys: List<String>,
    /** Room version and identity hash of the catalog schema this app reads. */
    val catalogSchemaVersion: Int,
    val catalogIdentityHash: String,
    /** When the catalog built into the APK was generated (ISO-8601). */
    val embeddedCatalogVersion: String,
) {
    val isConfigured: Boolean get() = baseUrl.startsWith("https://") && publicKeys.isNotEmpty()

    companion object {
        val NONE =
            BackendConfig(
                baseUrl = "",
                publicKeys = emptyList(),
                catalogSchemaVersion = 0,
                catalogIdentityHash = "",
                embeddedCatalogVersion = "",
            )
    }
}
