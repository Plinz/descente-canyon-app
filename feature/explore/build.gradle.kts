plugins {
    alias(libs.plugins.descente.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "fr.descentecanyon.app.feature.explore"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
