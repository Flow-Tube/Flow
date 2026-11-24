// Maven Central is read through Google's copy of it first: Maven Central answers GitHub's shared runner
// addresses with HTTP 429, which failed Lint, CodeQL and Unit tests on main. The copy only lags new
// releases by hours, and anything it lacks still comes from Maven Central itself.
pluginManagement {
    repositories {
        google()
        maven {
            name = "MavenCentralGoogleMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            name = "MavenCentralGoogleMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Flow"
include(":app")
include(":benchmark")
