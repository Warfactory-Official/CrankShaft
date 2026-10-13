import mov.movblock.tenon.gradle.TenonExtension

plugins {
    id("java-library")
    id("idea")
    id("mov.movblock.tenon")
}

// Version nodes share project names; module-specific groups keep their Gradle component identities distinct.
// Published loader coordinates are assigned explicitly by their publications.
group = "dev.engine_room.${parent!!.name}"
version = versionString

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

val tenon = extensions.getByType<TenonExtension>()
val loader = project.parent!!.name
tenon.compilerVersion.set(tenonVersion)
tenon.symbols.set(listOf("MC=${project.minecraftVersion}",
    if (loader == "fabric" || loader == "vanillinFabric") "FABRIC" else "!FABRIC",
    if (loader == "neoforge" || loader == "vanillinNeoForge") "NEOFORGE" else "!NEOFORGE"))
tenon.relocations.set(rootProject.file("tenon.relocations"))
tenon.access {
    enabled.set(true)
    mixins.set(true)
    if (loader == "neoforge" || loader == "vanillinNeoForge") {
        platform.set("neoforge")
        accessTransformers.from(rootProject.file("neoforge/versions/$minecraftVersion/src/main/resources/META-INF/accesstransformer.cfg"))
        accessTransformers.from(rootProject.file("neoforge/versions/$minecraftVersion/src/main/resources/META-INF/crankshaft-access.cfg"))
    } else {
        platform.set("fabric")
        accessWideners.from(rootProject.file("common/versions/$minecraftVersion/crankshaft.accesswidener")
            .takeIf { it.isFile } ?: rootProject.file("common/crankshaft.accesswidener"))
    }
}
tenon.macros {
    enabled.set(true)
    omit.set(true)
}
tenon.inject {
    enabled.set(true)
}
sourceSets.configureEach {
    val sourceSetName = name
    val sharedRoot = project.sharedModuleFile("src/$sourceSetName")
    val localRoot = project.file("src/$sourceSetName")
    java.srcDir(sharedRoot.resolve("java"))
    resources.srcDir(sharedRoot.resolve("resources"))
    java.excludeShadowedSharedSources(sharedRoot.resolve("java"), localRoot.resolve("java"))
    resources.excludeShadowedSharedSources(sharedRoot.resolve("resources"), localRoot.resolve("resources"))
    val sourceResources = resources
    tasks.named<ProcessResources>(processResourcesTaskName) { mergeMixinConfigs(sourceResources) }

    val aliasRoots = project.aliasSourceRoots(sourceSetName)
    if (project.parent!!.name == "common" && sourceSetName in listOf("lib", "backend")) {
        java.srcDir(project.rootProject.file("common/src/${sourceSetName}Aliases/java"))
    }
    tenon.sourceSet(this) { declarations.from(aliasRoots) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

tasks.withType<ProcessResources>().configureEach {
    inputs.properties(modMetadataProperties)
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(modMetadataProperties)
    }
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    maven("https://repo.warfactory.co/releases")
    maven("https://repo.warfactory.co/snapshots")
    maven("https://maven.caffeinemc.net/releases/")
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
