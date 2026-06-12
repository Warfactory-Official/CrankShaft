plugins {
    id("multiloader-platform")

    id("net.fabricmc.fabric-loom") version ("1.17.12")
}

base {
    archivesName = "vanillate-fabric"
}

repositories {
    maven("https://maven.caffeinemc.net/releases/")
}

val commonMainJava: Configuration = configurations.create("commonMainJava") {
    isCanBeResolved = true
}
val commonApiJava: Configuration = configurations.create("commonApiJava") {
    isCanBeResolved = true
}
val commonLibJava: Configuration = configurations.create("commonLibJava") {
    isCanBeResolved = true
}
val commonBackendJava: Configuration = configurations.create("commonBackendJava") {
    isCanBeResolved = true
}
val commonVanillinJava: Configuration = configurations.create("commonVanillinJava") {
    isCanBeResolved = true
}
val commonVanillinResources: Configuration = configurations.create("commonVanillinResources") {
    isCanBeResolved = true
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    implementation("net.fabricmc:fabric-loader:${fabricLoaderVersion}")

    compileOnly("net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}")

    compileOnly("net.caffeinemc:sodium-fabric-api:${sodiumVersion}")

    commonMainJava(project(path = ":common", configuration = "commonMainJava"))
    commonApiJava(project(path = ":common", configuration = "commonApiJava"))
    commonLibJava(project(path = ":common", configuration = "commonLibJava"))
    commonBackendJava(project(path = ":common", configuration = "commonBackendJava"))
    commonVanillinJava(project(path = ":common", configuration = "commonVanillinJava"))

    commonVanillinResources(project(path = ":common", configuration = "commonVanillinResources"))
}

sourceSets {
    named("main") {
        compileClasspath += commonMainJava
        compileClasspath += commonApiJava
        compileClasspath += commonLibJava
        compileClasspath += commonBackendJava
        compileClasspath += commonVanillinJava
        runtimeClasspath += commonMainJava
        runtimeClasspath += commonApiJava
        runtimeClasspath += commonLibJava
        runtimeClasspath += commonBackendJava
        runtimeClasspath += commonVanillinJava
    }
}

loom {
    mixin {
        useLegacyMixinAp = false
    }
}

tasks {
    jar {
        from(commonVanillinJava)

        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods"))
    }

    processResources {
        from(commonVanillinResources)
    }
}
