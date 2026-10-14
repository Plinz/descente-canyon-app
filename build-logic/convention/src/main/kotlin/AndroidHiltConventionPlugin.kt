import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt dependency injection (with KSP) for an Android module. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        dependencies {
            "implementation"(libs.lib("hilt-android"))
            "ksp"(libs.lib("hilt-android-compiler"))
        }
    }
}
