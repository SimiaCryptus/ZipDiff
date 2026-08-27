plugins {
    application
    kotlin("jvm")
     id("com.gradleup.shadow") version "9.6.1"
}

java {
    toolchain {
         // Must match zipdiff-plugin and the Kotlin jvmTarget set in the root build
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    implementation("org.json:json:20260814")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.cognotik.zipdiff.cli.ZipdiffCli")
}
tasks.jar {
     manifest {
         attributes["Main-Class"] = "io.cognotik.zipdiff.cli.ZipdiffCli"
     }
}
tasks.shadowJar {
     archiveBaseName.set("zipdiff-cli")
     archiveClassifier.set("all")
     archiveVersion.set(project.version.toString())
     mergeServiceFiles()
     manifest {
         attributes["Main-Class"] = "io.cognotik.zipdiff.cli.ZipdiffCli"
     }
}
tasks.build {
     dependsOn(tasks.shadowJar)
}