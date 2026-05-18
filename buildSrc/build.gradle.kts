plugins {
    `kotlin-dsl`
    idea
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

repositories {
    gradlePluginPortal()
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "Warfactory Releases"
        url = uri("https://repo.warfactory.co/releases")
        mavenContent { releasesOnly() }
    }
    maven {
        name = "Warfactory Snapshots"
        url = uri("https://repo.warfactory.co/snapshots")
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
    mavenLocal()
}

dependencies {
    // Must match `rfgVersion` in the parent project's gradle.properties
    implementation("com.gtnewhorizons.retrofuturagradle:com.gtnewhorizons.retrofuturagradle.gradle.plugin:2.0.2-5-gef67f10-SNAPSHOT")
}
