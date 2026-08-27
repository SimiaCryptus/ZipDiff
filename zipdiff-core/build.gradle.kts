plugins {
    application
    kotlin("jvm")
     id("com.gradleup.shadow") version "9.3.0"
}

java {
    toolchain {
         // Must match zipdiff-plugin and the Kotlin jvmTarget set in the root build
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("org.json:json:20230227")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
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