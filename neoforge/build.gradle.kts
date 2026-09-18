plugins {
    id("multiloader-platform")

    id("net.neoforged.moddev") version ("2.0.147")
    `maven-publish`
}

val quickPlay: String? = providers.gradleProperty("quickPlay").orNull
val vulkan = providers.gradleProperty("vulkan").isPresent

val sourcesJar = tasks.register<Jar>("sourcesJar") {
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

evaluationDependsOn(":vanillinNeoForge")

repositories {
    mavenLocal()
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

    compileOnly("net.caffeinemc:sodium-neoforge-api:${sodiumVersion}")

    jarJar(project(":vanillinNeoForge"))

    jarJar(project(":meshlet"))

    jarJar(project(":iris"))

    if (project.hasProperty("sodium") || project.hasProperty("iris")) {
        runtimeOnly("net.caffeinemc:sodium-neoforge:${sodiumVersion}")
    }
    if (project.hasProperty("iris")) {
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
            ideName = "NeoForge/Client"
            quickPlay?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
            if (vulkan) {
                programArguments.addAll("--graphicsBackend", "vulkan")
            }
            // Iris 1.11.4 swaps in 1-entry vertex format binding arrays; IDE-only GlCommandEncoder.validateDraw
            // indexes 16 => AIOOBE on the first Iris-mapped draw (also with flywheel:off).
            if (project.hasProperty("iris")) {
                jvmArguments.add("-Dneoforge.disableGlValidation=true")
            }
        }
    }

    mods {
        create("flywheel") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["api"])
            sourceSet(sourceSets["lib"])
            sourceSet(sourceSets["backend"])
            sourceSet(project(":common").sourceSets["main"])
            sourceSet(project(":common").sourceSets["api"])
            sourceSet(project(":common").sourceSets["lib"])
            sourceSet(project(":common").sourceSets["backend"])
        }
        create("vanillin") {
            sourceSet(project(":vanillinNeoForge").sourceSets["main"])
            sourceSet(project(":common").sourceSets["vanillin"])
        }
    }
}

neoForge.runs.named("client") {
    taskBefore(tasks.named("prepareClientRun"))
}

dependencies {
    runtimeOnly(project(":meshlet"))
    runtimeOnly(project(":iris"))
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
    main.runtimeClasspath += common + api.output + lib.output + backend.output
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
