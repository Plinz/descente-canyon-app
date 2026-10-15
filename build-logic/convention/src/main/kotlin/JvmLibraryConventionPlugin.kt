import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin module, without any Android dependency. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = BuildValues.JAVA_VERSION
            targetCompatibility = BuildValues.JAVA_VERSION
        }
        configureKotlinJvmTarget()
        configureUnitTests()

        dependencies {
            "testImplementation"(libs.lib("junit"))
            "testImplementation"(libs.lib("mockk"))
            "testImplementation"(libs.lib("kotlinx-coroutines-test"))
        }
    }
}
