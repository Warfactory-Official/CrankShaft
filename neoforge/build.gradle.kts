plugins {
    id("multiloader-platform")

    id("net.neoforged.moddev") version ("2.0.148")
    `maven-publish`
}

val quickPlay: String? = providers.gradleProperty("quickPlay").orNull
val vulkan = providers.gradleProperty("vulkan").isPresent
val experimentalIris = minecraftVersion == "26.3" && providers.gradleProperty("iris.experimental").isPresent
val iris = providers.gradleProperty("iris").isPresent || experimentalIris

if (experimentalIris) {
    tasks.matching { it.name.startsWith("run") }.configureEach {
        doFirst {
            check(providers.gradleProperty("iris.experimental.neoforgeJar").isPresent) {
                "The public experimental Iris reference is Fabric-only; set -Piris.experimental.neoforgeJar=<NeoForge build> for this run"
            }
        }
    }
}

evaluationDependsOn(siblingProjectPath("common"))

val sodiumNeoforgeImplementation = neoforgeSodiumImplementation()

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
tenon.accessorMixins(tasks.named<Jar>("jar"), sourceSets["main"])

publishing {
    publications {
        create<MavenPublication>("mavenJar") {
            groupId = "dev.engine_room"
            artifactId = "crankshaft-neoforge"
            version = project.version.toString()
            artifact(tasks.named("jar"))
            artifact(sourcesJar)
        }
    }
}

base {
    archivesName = "crankshaft-neoforge"
}

evaluationDependsOn(siblingProjectPath("vanillinNeoForge"))

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

    compileOnly("net.caffeinemc:sodium-neoforge-api:${sodiumVersion}")
    // Shared class metadata contains Fabric's CLASS-retained Environment annotation.
    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}") { isTransitive = false }
    compileOnly(sodiumNeoforgeImplementation)
    compileOnly("maven.modrinth:lambdynamiclights:$lambDynLightsVersion") { isTransitive = false }
    compileOnly("maven.modrinth:polytone:$polytoneCompileVersion") { isTransitive = false }
    compileOnly(nestedModLibraries("maven.modrinth:polytone:$polytoneCompileVersion", "codecui"))
    compileOnly(nestedModLibraries("maven.modrinth:lambdynamiclights:$lambDynLightsVersion",
        "lambdynamiclights-api", "spruceui", "yumi-mc-foundation"))

    jarJar(project(siblingProjectPath("vanillinNeoForge")))

    jarJar(project(siblingProjectPath("meshlet")))

    jarJar(project(path = siblingProjectPath("iris"), configuration = "neoforgeRuntimeElements"))

    if (project.hasProperty("sodium") || iris) {
        runtimeOnly("net.caffeinemc:sodium-neoforge:${sodiumVersion}")
    }
    if (experimentalIris) {
        runtimeOnly(files(providers.gradleProperty("iris.experimental.neoforgeJar").map { rootProject.file(it) }))
    } else if (iris) {
        runtimeOnly("maven.modrinth:iris:${irisVersion}-neoforge")
    }
}

sourceSets {
    create("api")
    create("lib")
    create("backend")
}

neoForge {
    version = neoVersion
    accessTransformers.from(file("src/main/resources/META-INF/accesstransformer.cfg"))
    validateAccessTransformers = true

    addModdingDependenciesTo(sourceSets["api"])
    addModdingDependenciesTo(sourceSets["lib"])
    addModdingDependenciesTo(sourceSets["backend"])

    runs {
        create("client") {
            client()
            ideName = "NeoForge/$minecraftVersion/Client"
            quickPlay?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
            if (vulkan) {
                programArguments.addAll("--graphicsBackend", "vulkan")
            }
            // Iris custom passes replace native shader bindings; NeoForge's IDE validation still checks the
            // placeholder pipeline. The 26.3 frontend renamed the dev-only validation switch.
            if (iris) {
                val validationProperty =
                    if (minecraftVersion == "26.2") "disableGlValidation" else "disableBlaze3DValidation"
                jvmArguments.add("-Dneoforge.$validationProperty=true")
            }
        }
    }

    mods {
        create("crankshaft") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["api"])
            sourceSet(sourceSets["lib"])
            sourceSet(sourceSets["backend"])
            sourceSet(project(siblingProjectPath("common")).sourceSets["api"])
            sourceSet(project(siblingProjectPath("common")).sourceSets["lib"])
        }
        create("vanillate") {
            sourceSet(project(siblingProjectPath("vanillinNeoForge")).sourceSets["main"])
            sourceSet(project(siblingProjectPath("common")).sourceSets["vanillin"])
        }
    }
}

neoForge.runs.named("client") {
    taskBefore(tasks.named("prepareClientRun"))
}

dependencies {
    runtimeOnly(project(siblingProjectPath("meshlet")))
    runtimeOnly(project(path = siblingProjectPath("iris"), configuration = "neoforgeRuntimeElements"))
}

sourceSets {
    val common = files(commonMainJava, commonApiJava, commonLibJava, commonBackendJava)
    val api = getByName("api")
    val lib = getByName("lib")
    val backend = getByName("backend")
    val main = getByName("main")

    api.compileClasspath += common
    api.runtimeClasspath += common

    lib.compileClasspath += common + api.output
    lib.runtimeClasspath += common + api.output

    backend.compileClasspath += common + api.output + lib.output
    backend.runtimeClasspath += common + api.output + lib.output

    main.compileClasspath += common + api.output + lib.output + backend.output
    main.runtimeClasspath += files(commonApiJava, commonLibJava) + api.output + lib.output + backend.output
}

tenon.sourceSet(sourceSets["main"]) {
    access.mixinInputs.from(commonMainJava, commonBackendJava)
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
