plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.18.2")
}

base {
    archivesName = "crankshaft-common"
}

val NEOFORGE_MIXIN = "0.17.3+mixin.0.8.7"

sourceSets {
    val main = getByName("main")
    val api = create("api")
    val lib = create("lib")
    val backend = create("backend")
    val vanillin = create("vanillin")

    api.apply {
        java {
            compileClasspath += main.compileClasspath
        }
    }

    lib.apply {
        java {
            compileClasspath += main.compileClasspath
            compileClasspath += api.output
        }
    }

    backend.apply {
        java {
            compileClasspath += main.compileClasspath
            compileClasspath += api.output
            compileClasspath += lib.output
        }
    }

    main.apply {
        java {
            compileClasspath += api.output
            compileClasspath += lib.output
            compileClasspath += backend.output
        }
    }

    vanillin.apply {
        java {
            compileClasspath += main.compileClasspath
            compileClasspath += api.output
            compileClasspath += lib.output
            compileClasspath += main.output
        }
    }
}

fun nestedJars(notation: String, vararg names: String): Set<File> {
    val jar = configurations.detachedConfiguration(dependencies.create(notation)).apply { isTransitive = false }
    return zipTree(jar.singleFile).matching { names.forEach { include("META-INF/jars/$it-*.jar") } }.files
}

repositories {
    mavenLocal()
    maven("https://maven.caffeinemc.net/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    compileOnly("net.caffeinemc:sodium-fabric-api:${sodiumVersion}")
    compileOnly("net.caffeinemc:sodium-fabric:${sodiumVersion}")
    compileOnly("maven.modrinth:voxy:${voxyVersion}")
    compileOnly("maven.modrinth:entityculling:${entityCullingVersion}") { isTransitive = false }
    compileOnly("maven.modrinth:lambdynamiclights:${lambDynLightsVersion}") { isTransitive = false }
    compileOnly("maven.modrinth:entitytexturefeatures:${etfVersion}") { isTransitive = false }
    compileOnly("maven.modrinth:entity-model-features:${emfVersion}") { isTransitive = false }
    compileOnly("maven.modrinth:polytone:${polytoneVersion}") { isTransitive = false }
    compileOnly("maven.modrinth:vitrail-shaders:${vitrailVersion}") { isTransitive = false }
    // Jar-in-jar'd libraries their APIs expose: Polytone's content managers -> codecui; LambDynamicLights -> its
    // behavior API, SpruceUI and Yumi supertypes.
    compileOnly(files(provider { nestedJars("maven.modrinth:polytone:${polytoneVersion}", "codecui") }))
    compileOnly(files(provider {
        nestedJars("maven.modrinth:lambdynamiclights:${lambDynLightsVersion}", "lambdynamiclights-api", "spruceui",
                "yumi-mc-foundation")
    }))

    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")

    compileOnly("net.fabricmc:sponge-mixin:$NEOFORGE_MIXIN")
    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
}

// Shared bytecode runs on NeoForge's Mixin 0.17.3 + MixinExtras 0.5.4: compiling against 0.17.4 encodes
// @Redirect/@ModifyArg `at` as an array, which that runtime cannot read. Loom adds the loader's Mixin as a
// first-level loaderLibraries dependency, so only a resolution rule overrides it.
configurations.named("compileClasspath") {
    resolutionStrategy.eachDependency {
        if (requested.group == "net.fabricmc" && requested.name == "sponge-mixin") {
            useVersion(NEOFORGE_MIXIN)
        }
    }
}

loom {
    accessWidenerPath.set(file("crankshaft.accesswidener"))

    mixin {
        useLegacyMixinAp = false
    }
}

fun exportSourceSetJava(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Java") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.getByName<JavaCompile>(sourceSet.compileJavaTaskName)
    artifacts.add(configuration.name, compileTask.destinationDirectory) {
        builtBy(compileTask)
    }
}

fun exportSourceSetSources(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Sources") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.register<Copy>(sourceSet.getTaskName("process", "sources")) {
        from(sourceSet.allSource)
        into(file(project.layout.buildDirectory).resolve("sources").resolve(sourceSet.name))
    }.get()
    artifacts.add(configuration.name, compileTask.destinationDir) {
        builtBy(compileTask)
    }
}

fun exportSourceSetResources(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Resources") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    val compileTask = tasks.getByName<ProcessResources>(sourceSet.processResourcesTaskName)
    compileTask.apply {
        exclude("**/README.txt")
        exclude("/*.accesswidener")
    }

    artifacts.add(configuration.name, compileTask.destinationDir) {
        builtBy(compileTask)
    }
}

fun exportSourceSet(name: String, sourceSet: SourceSet) {
    exportSourceSetJava(name, sourceSet)
    exportSourceSetSources(name, sourceSet)
    exportSourceSetResources(name, sourceSet)
}

exportSourceSet("commonMain", sourceSets["main"])
exportSourceSet("commonApi", sourceSets["api"])
exportSourceSet("commonLib", sourceSets["lib"])
exportSourceSet("commonBackend", sourceSets["backend"])
exportSourceSet("commonVanillin", sourceSets["vanillin"])

tasks.jar { enabled = false }
