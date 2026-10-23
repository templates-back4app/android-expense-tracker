pluginManagement {
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
        // Parse-SDK-Android is published on JitPack only.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.parse-community.*") } }
    }
}
rootProject.name = "android-expense-tracker"
include(":app")
