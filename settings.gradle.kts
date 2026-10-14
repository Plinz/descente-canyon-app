pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DescenteCanyon"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:testing")
include(":core:ui")
include(":feature:home")
include(":feature:explore")
include(":feature:canyon")
include(":feature:contribute")
include(":feature:favorites")
include(":feature:notifications")
include(":feature:users")
