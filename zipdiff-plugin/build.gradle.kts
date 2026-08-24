plugins {
    `java-gradle-plugin`
    kotlin("jvm")
}
java {
     toolchain {
         languageVersion.set(JavaLanguageVersion.of(21))
     }
}


dependencies {
     // gradleApi() (added by `java-gradle-plugin`) already brings Gradle's embedded
     // kotlin-stdlib, so do NOT add another kotlin-stdlib here.
     compileOnly(gradleApi())
    implementation(project(":zipdiff-core"))
}

gradlePlugin {
    plugins {
        register("zipdiffPatch") {
            id = "io.cognotik.zipdiff-patch"
            implementationClass = "io.cognotik.zipdiff.plugin.ZipdiffPatchPlugin"
        }
    }
}