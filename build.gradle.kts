import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
     // Gradle 9.6 exposes kotlin-stdlib 2.3.21 (metadata 2.3.0) on the plugin
     // compile classpath via gradleApi(). The Kotlin compiler must be new enough
     // to read that metadata: 1.9.x reads <= 2.0.0, 2.2.x reads <= 2.3.0.
     kotlin("jvm") version "2.2.20" apply false
}

allprojects {
    apply(plugin = "base")
    repositories {
        mavenCentral()
    }
}

subprojects {
    group = "org.zipdiff"
    version = "1.0.0-SNAPSHOT"
     // Keep Java and Kotlin on the same JVM target everywhere.
      // Pin 21 everywhere (Java toolchain + Kotlin jvmTarget must agree).
     plugins.withId("org.jetbrains.kotlin.jvm") {
         extensions.configure<JavaPluginExtension> {
             toolchain {
                 languageVersion.set(JavaLanguageVersion.of(21))
             }
         }
         tasks.withType<JavaCompile>().configureEach {
             options.release.set(21)
         }
         tasks.withType<KotlinCompile>().configureEach {
             compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
         }
     }
}