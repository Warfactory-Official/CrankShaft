plugins {
    id("multiloader-platform")

    id("net.fabricmc.fabric-loom") version ("1.18.4")
    `maven-publish`
}

val quickPlay: String? = providers.gradleProperty("quickPlay").orNull
val vulkan = providers.gradleProperty("vk").isPresent
val vulkanValidation = providers.gradleProperty("vkvalidation").isPresent
val experimentalIris = minecraftVersion == "26.3" && providers.gradleProperty("iris.experimental").isPresent

evaluationDependsOn(siblingProjectPath("common"))

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier = "sources"
    manifest.attributes("Source-Dialect" to "Tenon")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    for (set in listOf("main", "api", "lib", "backend")) {
        from(project(siblingProjectPath("common")).sourceSets[set].allJava)
        from(sourceSets[set].allJava)
    }
}
tenon.sourcesJar(sourcesJar, sourceSets["main"])

publishing {
    publications {
        create<MavenPublication>("mavenJar") {
            groupId = "dev.engine_room"
            artifactId = "crankshaft-fabric"
            version = project.version.toString()
            artifact(tasks.named("jar"))
            artifact(sourcesJar)
        }
    }
}

base {
    archivesName = "crankshaft-fabric"
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
val commonModResources: Configuration = configurations.create("commonModResources") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    commonMainJava(project(path = siblingProjectPath("common"), configuration = "commonMainJava"))
    commonApiJava(project(path = siblingProjectPath("common"), configuration = "commonApiJava"))
    commonLibJava(project(path = siblingProjectPath("common"), configuration = "commonLibJava"))
    commonBackendJava(project(path = siblingProjectPath("common"), configuration = "commonBackendJava"))

    commonModResources(project(path = siblingProjectPath("common"), configuration = "commonMainResources"))
    commonModResources(project(path = siblingProjectPath("common"), configuration = "commonApiResources"))
    commonModResources(project(path = siblingProjectPath("common"), configuration = "commonLibResources"))
    commonModResources(project(path = siblingProjectPath("common"), configuration = "commonBackendResources"))
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

    include(project(siblingProjectPath("vanillinFabric")))

    include(project(siblingProjectPath("meshlet")))

    include(project(siblingProjectPath("iris")))

    localRuntime(project(siblingProjectPath("vanillinFabric")))

    localRuntime(project(siblingProjectPath("meshlet")))

    localRuntime(project(siblingProjectPath("iris")))

    if (project.hasProperty("sodium") || project.hasProperty("iris") || experimentalIris) {
        localRuntime("net.caffeinemc:sodium-fabric:${sodiumVersion}")
    }
    if (experimentalIris) {
        localRuntime(project(path = siblingProjectPath("iris"), configuration = "experimentalIrisReference"))
    } else if (project.hasProperty("iris")) {
        localRuntime("maven.modrinth:iris:${irisVersion}-fabric")
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
            displayName = "Fabric/$minecraftVersion/Client"
            generateRunConfig = true
            runDirectory = file("run")
            quickPlay?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
            if (vulkan) {
                programArguments.addAll("--graphicsBackend", "vulkan")
            }
            if (vulkanValidation) {
                programArguments.add("--vulkanValidation")
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

        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods/$minecraftVersion"))
    }

    processResources {
        from(commonModResources)
    }
}
