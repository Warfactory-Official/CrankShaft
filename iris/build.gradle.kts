plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.18.2")
}

base {
    archivesName = "crankshaft-iris"
}

val NEOFORGE_MIXIN = "0.17.3+mixin.0.8.7"

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
    compileOnly("maven.modrinth:iris:${irisVersion}-fabric")
    compileOnly("io.github.douira:glsl-transformer:3.0.0-pre3")
    compileOnly("org.antlr:antlr4-runtime:4.13.1")

    compileOnly("org.jspecify:jspecify:1.0.0")

    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("net.fabricmc:sponge-mixin:$NEOFORGE_MIXIN")

    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
}

// Same NeoForge Mixin pin as :common (0.17.4 array-encodes @Redirect/@ModifyArg `at`).
configurations.named("compileClasspath") {
    resolutionStrategy.eachDependency {
        if (requested.group == "net.fabricmc" && requested.name == "sponge-mixin") {
            useVersion(NEOFORGE_MIXIN)
        }
    }
}

loom {
    // Compile-time only: Iris's own AW/AT widens these at runtime, and this module is inert without Iris.
    accessWidenerPath.set(file("iris.accesswidener"))

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
        from(rootProject.file("LICENSE")) { into("META-INF") }
        from("THIRD_PARTY_NOTICES") { into("META-INF") }
    }
}
