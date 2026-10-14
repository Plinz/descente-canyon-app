import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Feature module: Compose screens and their view models. A feature only sees the domain layer and
 * the shared UI module, never the data layer nor another feature.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("descente.android.library")
        pluginManager.apply("descente.android.compose")
        pluginManager.apply("descente.android.hilt")

        dependencies {
            "implementation"(project(":core:domain"))
            "implementation"(project(":core:ui"))
            "implementation"(libs.lib("androidx-core-ktx"))
            "implementation"(libs.lib("androidx-activity-compose"))
            "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
            "implementation"(libs.lib("androidx-lifecycle-viewmodel-compose"))
            "implementation"(libs.lib("androidx-navigation-compose"))
            "implementation"(libs.lib("androidx-hilt-navigation-compose"))
            "implementation"(libs.lib("kotlinx-coroutines-android"))
            "testImplementation"(project(":core:testing"))
            "testImplementation"(libs.lib("turbine"))
        }
    }
}
