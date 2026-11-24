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
        // Robolectric's 150 MB Android image comes from Google's copy of Maven Central: Maven Central
        // itself answered CI runners' downloads of it with HTTP 429.
        exclusiveContent {
            forRepository {
                maven {
                    name = "MavenCentralGoogleMirror"
                    url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Flow"
include(":app")
include(":benchmark")
