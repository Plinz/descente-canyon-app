plugins {
    alias(libs.plugins.descente.android.library)
    alias(libs.plugins.descente.android.compose)
    alias(libs.plugins.descente.android.hilt)
}

android {
    namespace = "fr.descentecanyon.app.core.ui"
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)

    // The map widget is shared by the canyon and explore features, which pass MapLibre types.
    api(libs.maplibre)
    api(libs.play.services.location)
}
