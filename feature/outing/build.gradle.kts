plugins {
    alias(libs.plugins.descente.android.feature)
}

android {
    namespace = "fr.descentecanyon.app.feature.outing"
}

dependencies {
    implementation(libs.qrcodegen)
}
