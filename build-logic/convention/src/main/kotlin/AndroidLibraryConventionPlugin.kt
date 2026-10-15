import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Android library module: SDK levels, Java/Kotlin targets and the unit test toolkit. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = BuildValues.COMPILE_SDK
            defaultConfig {
                minSdk = BuildValues.MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = BuildValues.JAVA_VERSION
                targetCompatibility = BuildValues.JAVA_VERSION
            }
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
