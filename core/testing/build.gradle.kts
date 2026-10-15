plugins {
    alias(libs.plugins.descente.android.library)
}

android {
    namespace = "fr.descentecanyon.app.testing"
}

dependencies {
    api(project(":core:domain"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
