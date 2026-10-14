plugins {
    `kotlin-dsl`
}

group = "fr.descentecanyon.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = libs.plugins.descente.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.descente.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = libs.plugins.descente.android.hilt.get().pluginId
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = libs.plugins.descente.android.feature.get().pluginId
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.descente.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
