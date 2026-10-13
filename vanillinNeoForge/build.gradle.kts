plugins {
    id("multiloader-platform")

    id("net.neoforged.moddev") version ("2.0.148")
}

base {
    archivesName = "vanillate-neoforge"
}

val commonMainJava: Configuration = configurations.create("commonMainJava") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val commonApiJava: Configuration = configurations.create("commonApiJava") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val commonLibJava: Configuration = configurations.create("commonLibJava") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val commonBackendJava: Configuration = configurations.create("commonBackendJava") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val commonVanillinJava: Configuration = configurations.create("commonVanillinJava") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val commonVanillinResources: Configuration = configurations.create("commonVanillinResources") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    compileOnly("net.caffeinemc:sodium-neoforge-api:${sodiumVersion}")

    commonMainJava(project(path = siblingProjectPath("common"), configuration = "commonMainJava"))
    commonApiJava(project(path = siblingProjectPath("common"), configuration = "commonApiJava"))
    commonLibJava(project(path = siblingProjectPath("common"), configuration = "commonLibJava"))
    commonBackendJava(project(path = siblingProjectPath("common"), configuration = "commonBackendJava"))
    commonVanillinJava(project(path = siblingProjectPath("common"), configuration = "commonVanillinJava"))

    commonVanillinResources(project(path = siblingProjectPath("common"), configuration = "commonVanillinResources"))
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
        create("vanillate") {
            sourceSet(sourceSets["main"])
            sourceSet(project(siblingProjectPath("common")).sourceSets["vanillin"])
        }
    }
}

tasks {
    jar {
        from(commonVanillinJava)

        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods/$minecraftVersion"))
    }

    processResources {
        from(commonVanillinResources)
    }
}
