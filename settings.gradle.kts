import java.util.*

rootProject.name = "crankshaft-multiversion"

pluginManagement {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases/") }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// A profile is the registration point for a Minecraft version; source overrides remain optional.
val versionProfiles = file("versions").listFiles()!!.filter {
    it.isDirectory && it.resolve("gradle.properties").isFile
}.sortedBy { it.name }
check(versionProfiles.isNotEmpty()) { "No Minecraft profiles found under versions/" }
val supportedVersions = versionProfiles.map { it.name }
val modules = listOf("common", "meshlet", "iris", "neoforge", "fabric", "vanillinNeoForge", "vanillinFabric")

providers.gradleProperty("tenon.source").orNull?.let { source ->
    includeBuild(file(source)) {
        dependencySubstitution {
            for (tenonModule in listOf("core", "macro", "preprocess", "macro-api", "gradle-support", "access", "inject")) {
                substitute(module("mov.movblock.tenon:tenon-$tenonModule")).using(project(":$tenonModule"))
            }
        }
    }
}

for (module in modules) {
    include(module)
    project(":$module").buildFileName = "branch.gradle.kts"
    for (version in supportedVersions) {
        val path = ":$module:$version"
        include(path)
        // Gradle 9 rejects missing project dirs; override-free nodes have none in git.
        project(path).projectDir = file("$module/versions/$version").apply { mkdirs() }
        project(path).buildFileName = "../../build.gradle.kts"
    }
}

val versionProperties = supportedVersions.associateWith { version ->
    Properties().apply {
        file("versions/$version/gradle.properties").inputStream().use(::load)
    }
}

gradle.beforeProject {
    versionProperties[name]?.forEach { key, value ->
        extensions.extraProperties.set(key.toString(), value.toString())
    }
}
