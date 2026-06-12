plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.17.12")
}

base {
    archivesName = "crankshaft-common"
}

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

repositories {
    mavenLocal()
    maven("https://maven.caffeinemc.net/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    compileOnly("net.caffeinemc:sodium-fabric-api:${sodiumVersion}")
    compileOnly("net.caffeinemc:sodium-fabric:${sodiumVersion}")

    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")

    compileOnly("net.fabricmc:sponge-mixin:0.13.2+mixin.0.8.5")
    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
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
