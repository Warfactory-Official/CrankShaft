import java.util.Properties

plugins {
    `kotlin-dsl`
}

repositories {
    maven("https://repo.warfactory.co/releases")
    maven("https://repo.warfactory.co/snapshots")
    mavenCentral()
    gradlePluginPortal()
}

val mainProperties = Properties().apply {
    file("../gradle.properties").inputStream().use(::load)
}
dependencies {
    val tenonVersion = providers.gradleProperty("tenon_version")
        .orElse(mainProperties.getProperty("tenon_version"))
    implementation("mov.movblock.tenon:tenon-gradle-support:${tenonVersion.get()}")
}
