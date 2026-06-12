plugins {
    id("multiloader-platform")

    id("net.fabricmc.fabric-loom") version ("1.17.12")
    `maven-publish`
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier = "sources"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    for (set in listOf("main", "api", "lib", "backend")) {
        from(project(":common").file("src/$set/java"))
        from(file("src/$set/java"))
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJar") {
            groupId = "dev.engine_room"
            artifactId = "crankshaft"
            version = project.version.toString()
            artifact(tasks.named("jar")) { classifier = "fabric" }
            artifact(sourcesJar) { classifier = "fabric-sources" }
        }
    }
}

base {
    archivesName = "crankshaft-fabric"
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
val commonModResources: Configuration = configurations.create("commonModResources") {
    isCanBeResolved = true
}

dependencies {
    commonMainJava(project(path = ":common", configuration = "commonMainJava"))
    commonApiJava(project(path = ":common", configuration = "commonApiJava"))
    commonLibJava(project(path = ":common", configuration = "commonLibJava"))
    commonBackendJava(project(path = ":common", configuration = "commonBackendJava"))

    commonModResources(project(path = ":common", configuration = "commonMainResources"))
    commonModResources(project(path = ":common", configuration = "commonApiResources"))
    commonModResources(project(path = ":common", configuration = "commonLibResources"))
    commonModResources(project(path = ":common", configuration = "commonBackendResources"))
}

repositories {
    mavenLocal()
    maven("https://maven.caffeinemc.net/releases/")
}

sourceSets {
    val main = getByName("main")
    val api = create("api")
    val lib = create("lib")
    val backend = create("backend")

    val common = files(commonMainJava, commonApiJava, commonLibJava, commonBackendJava)

    api.apply {
        compileClasspath += main.compileClasspath + common
        runtimeClasspath += main.runtimeClasspath + common
    }
    lib.apply {
        compileClasspath += main.compileClasspath + common + api.output
        runtimeClasspath += main.runtimeClasspath + common + api.output
    }
    backend.apply {
        compileClasspath += main.compileClasspath + common + api.output + lib.output
        runtimeClasspath += main.runtimeClasspath + common + api.output + lib.output
    }
    main.apply {
        compileClasspath += common + api.output + lib.output + backend.output
        runtimeClasspath += common + api.output + lib.output + backend.output
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    compileOnly("net.caffeinemc:sodium-fabric-api:${sodiumVersion}")

    implementation("net.fabricmc:fabric-loader:${fabricLoaderVersion}")

    implementation("net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}")

    include(project(":vanillinFabric"))

    include(project(":meshlet"))

    localRuntime(project(":vanillinFabric"))

    localRuntime(project(":meshlet"))

    if (project.hasProperty("sodium")) {
        localRuntime("net.caffeinemc:sodium-fabric:${sodiumVersion}")
    }
}

loom {
    accessWidenerPath.set(file("src/main/resources/crankshaft.accesswidener"))

    mixin {
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            client()
            configName = "Fabric/Client"
            ideConfigGenerated(true)
            runDir("run")
            if (project.hasProperty("quickPlay")) {
                programArgs("--quickPlaySingleplayer", project.property("quickPlay").toString())
            }
            if (project.hasProperty("vk")) {
                programArgs("--graphicsBackend", "vulkan")
            }
            if (project.hasProperty("vkvalidation")) {
                programArgs("--vulkanValidation")
            }
        }
    }
}

tasks {
    jar {
        from(commonMainJava)
        from(commonApiJava)
        from(commonLibJava)
        from(commonBackendJava)
        from(sourceSets["api"].output)
        from(sourceSets["lib"].output)
        from(sourceSets["backend"].output)

        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods"))
    }

    processResources {
        from(commonModResources)
    }
}
