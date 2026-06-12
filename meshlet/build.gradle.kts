plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.17.12")
}

base {
    archivesName = "meshlet"
}

val commonMainJava: Configuration = configurations.create("commonMainJava") { isCanBeResolved = true }
val commonApiJava: Configuration = configurations.create("commonApiJava") { isCanBeResolved = true }
val commonLibJava: Configuration = configurations.create("commonLibJava") { isCanBeResolved = true }
val commonBackendJava: Configuration = configurations.create("commonBackendJava") { isCanBeResolved = true }

dependencies {
    commonMainJava(project(path = ":common", configuration = "commonMainJava"))
    commonApiJava(project(path = ":common", configuration = "commonApiJava"))
    commonLibJava(project(path = ":common", configuration = "commonLibJava"))
    commonBackendJava(project(path = ":common", configuration = "commonBackendJava"))
}

repositories {
    mavenLocal()
    maven("https://maven.caffeinemc.net/releases/")
}

sourceSets {
    named("main") {
        compileClasspath += commonMainJava
        compileClasspath += commonApiJava
        compileClasspath += commonLibJava
        compileClasspath += commonBackendJava
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    compileOnly("net.caffeinemc:sodium-fabric:${sodiumVersion}")

    compileOnly("org.jspecify:jspecify:1.0.0")

    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("net.fabricmc:sponge-mixin:0.13.2+mixin.0.8.5")

    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
}

loom {
    mixin {
        useLegacyMixinAp = false
    }
}

tasks {
    processResources {
        inputs.property("version", version)
        filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
            expand(mapOf("version" to inputs.properties["version"]))
        }
    }

    jar {
        from("LICENSE") { into("META-INF") }
        from("THIRD_PARTY_NOTICES") { into("META-INF") }
    }
}
