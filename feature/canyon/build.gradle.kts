plugins {
    alias(libs.plugins.descente.android.feature)
}

android {
    namespace = "fr.descentecanyon.app.feature.canyon"
}

dependencies {
    implementation(libs.coil.compose)
}
