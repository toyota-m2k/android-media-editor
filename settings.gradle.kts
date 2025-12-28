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
        mavenLocal()
        maven (url="https://jitpack.io")
    }
}

rootProject.name = "android-media-editor"
val isJitpack = System.getenv("JITPACK") == "true"
if (!isJitpack) {
    include(":appMediaEditor")
}
include(":libMediaEditor")
