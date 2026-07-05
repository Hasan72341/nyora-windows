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
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Required by compose-webview-multiplatform → KCEF → JCEF, which pull
        // org.jogamp gluegen-rt / jogl-all 2.5.0 (only hosted here, not Central).
        maven { url = uri("https://jogamp.org/deployment/maven/") }
    }
}

rootProject.name = "nyora-windows"

include(":shared")
include(":desktopApp")
