import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/** Jetpack Compose for an Android module (compiler plugin, BOM and core UI artifacts). */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true

        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            "implementation"(bom)
            "androidTestImplementation"(bom)
            "implementation"(libs.lib("androidx-ui"))
            "implementation"(libs.lib("androidx-ui-graphics"))
            "implementation"(libs.lib("androidx-ui-tooling-preview"))
            "implementation"(libs.lib("androidx-material3"))
            "implementation"(libs.lib("androidx-material-icons-extended"))
            "debugImplementation"(libs.lib("androidx-ui-tooling"))
        }
    }
}
