pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        kotlin("jvm") version "2.2.21"
        kotlin("android") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
        id("com.android.application") version "8.13.2"
        id("com.google.devtools.ksp") version "2.2.21-2.0.4"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "personal-training"
include(":shared", ":server")
if (!providers.gradleProperty("serverOnly").isPresent) include(":android")
