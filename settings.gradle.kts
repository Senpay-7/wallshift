pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's Maven Central mirror, used only for Robolectric's large runtime jars
        // (Maven Central redirects those to a CDN that some networks block).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}
rootProject.name = "Wallshift"
include(":app", ":baselineprofile")
