pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
     id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}


dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "zipdiff"

include(":zipdiff-core")
include(":zipdiff-plugin")