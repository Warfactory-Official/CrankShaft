pluginManagement {
    repositories {
        maven {
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroup("com.gtnewhorizons.retrofuturagradle")
            }
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
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
    val rfgVersion: String by settings
    plugins {
        id("com.gtnewhorizons.retrofuturagradle") version rfgVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = rootProject.projectDir.name
