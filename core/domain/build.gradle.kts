plugins {
    alias(libs.plugins.descente.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    api(libs.kotlinx.serialization.json)
}
