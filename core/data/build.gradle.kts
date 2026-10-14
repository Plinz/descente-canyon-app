plugins {
    alias(libs.plugins.descente.android.library)
    alias(libs.plugins.descente.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.descentecanyon.app.data"

    testOptions {
        unitTests.all {
            // ONNX Runtime loads its native library in the model contract tests.
            it.jvmArgs("--enable-native-access=ALL-UNNAMED")
            it.systemProperty("repoRoot", rootDir.absolutePath)
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Local storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Network and scraping: OkHttp is the only HTTP stack, Jsoup only parses
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // On-device debit model
    implementation(libs.onnxruntime.android)

    testImplementation(libs.turbine)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
