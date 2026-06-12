plugins {
    id("multiloader-platform")

    id("net.neoforged.moddev") version ("2.0.141")
}

base {
    archivesName = "vanillate-neoforge"
}

repositories {
    maven("https://maven.neoforged.net/releases/")

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
    compileOnly("net.caffeinemc:sodium-neoforge-api:${sodiumVersion}")

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

neoForge {
    version = neoVersion

    mods {
        create("vanillin") {
            sourceSet(sourceSets["main"])
            sourceSet(project(":common").sourceSets["vanillin"])
        }
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
