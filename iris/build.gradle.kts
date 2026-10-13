plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.18.4")
}

base {
    archivesName = "crankshaft-iris"
}

tenon.symbols.set(listOf("MC=$minecraftVersion", "FABRIC", "!NEOFORGE"))

val commonMainJava: Configuration =
    configurations.create("commonMainJava") { isCanBeResolved = true; isCanBeConsumed = false }
val commonApiJava: Configuration =
    configurations.create("commonApiJava") { isCanBeResolved = true; isCanBeConsumed = false }
val commonLibJava: Configuration =
    configurations.create("commonLibJava") { isCanBeResolved = true; isCanBeConsumed = false }
val commonBackendJava: Configuration =
    configurations.create("commonBackendJava") { isCanBeResolved = true; isCanBeConsumed = false }

dependencies {
    commonMainJava(project(path = siblingProjectPath("common"), configuration = "commonMainJava"))
    commonApiJava(project(path = siblingProjectPath("common"), configuration = "commonApiJava"))
    commonLibJava(project(path = siblingProjectPath("common"), configuration = "commonLibJava"))
    commonBackendJava(project(path = siblingProjectPath("common"), configuration = "commonBackendJava"))
}

sourceSets {
    named("main") {
        compileClasspath += commonMainJava
        compileClasspath += commonApiJava
        compileClasspath += commonLibJava
        compileClasspath += commonBackendJava
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    compileOnly("net.caffeinemc:sodium-fabric:${sodiumVersion}")
    compileOnly("maven.modrinth:iris:${irisVersion}-fabric")
    compileOnly("io.github.douira:glsl-transformer:${glslTransformerVersion}")
    compileOnly("org.antlr:antlr4-runtime:${antlrRuntimeVersion}")

    compileOnly("org.jspecify:jspecify:1.0.0")

    compileOnly("io.github.llamalad7:mixinextras-common:${mixinExtrasVersion}")
    annotationProcessor("io.github.llamalad7:mixinextras-common:${mixinExtrasVersion}")
    compileOnly("net.fabricmc:sponge-mixin:${mixinVersion}")

    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
}

// Shared Mixin bytecode follows the same version-specific NeoForge runtime as the common module.
configurations.named("compileClasspath") {
    resolutionStrategy.eachDependency {
        if (requested.group == "net.fabricmc" && requested.name == "sponge-mixin") {
            useVersion(mixinVersion)
        }
    }
}

loom {
    // Compile-time only: Iris's own AW/AT widens these at runtime, and this module is inert without Iris.
    accessWidenerPath.set(versionedModuleFile("iris.accesswidener"))

    mixin {
        useLegacyMixinAp = false
    }
}

tasks {
    jar {
        from(rootProject.file("LICENSE")) { into("META-INF") }
        from(sharedModuleFile("THIRD_PARTY_NOTICES")) { into("META-INF") }
    }
}

// Foreign-mod access requires loader-specific bytecode.
val neoforgeMain = sourceSets.create("neoforge") {
    java.setSrcDirs(sourceSets.main.get().java.srcDirs)
    resources.setSrcDirs(sourceSets.main.get().resources.srcDirs)
    java.excludeShadowedSharedSources(sharedModuleFile("src/main/java"), file("src/main/java"))
    resources.excludeShadowedSharedSources(sharedModuleFile("src/main/resources"), file("src/main/resources"))
    compileClasspath += sourceSets.main.get().compileClasspath.filter {
        !it.name.startsWith("iris-") && !it.name.startsWith("sodium-fabric-")
    }
}
val sodiumNeoforgeImplementation = neoforgeSodiumImplementation()
dependencies {
    add(neoforgeMain.compileOnlyConfigurationName, "maven.modrinth:iris:${irisVersion}-neoforge")
    add(neoforgeMain.compileOnlyConfigurationName, "net.caffeinemc:sodium-neoforge-api:${sodiumVersion}")
    add(neoforgeMain.compileOnlyConfigurationName, sodiumNeoforgeImplementation)
}

fun configureNeoForgeAccess(sourceSet: SourceSet) {
    tenon.sourceSet(sourceSet) {
        symbols.set(listOf("MC=$minecraftVersion", "!FABRIC", "NEOFORGE"))
        access {
            platform.set("neoforge")
            accessWideners.setFrom(emptyList<File>())
            accessTransformers.from(
                rootProject.file("neoforge/versions/$minecraftVersion/src/main/resources/META-INF/accesstransformer.cfg"),
                rootProject.file("neoforge/versions/$minecraftVersion/src/main/resources/META-INF/crankshaft-access.cfg")
            )
        }
    }
}
configureNeoForgeAccess(neoforgeMain)

val neoforgeJar = tasks.register<Jar>("neoforgeJar") {
    archiveClassifier = "neoforge"
    from(neoforgeMain.output)
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(sharedModuleFile("THIRD_PARTY_NOTICES")) { into("META-INF") }
}
val neoforgeAccessSources = mutableListOf(neoforgeMain)
configurations.create("neoforgeRuntimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(neoforgeJar)
}

if (minecraftVersion == "26.3") {
    val experimental = sourceSets.create("experimental") {
        // Compile the two Iris APIs separately; select their mixins before linking.
        compileClasspath += sourceSets.main.get().output
        compileClasspath += sourceSets.main.get().compileClasspath.filter { !it.name.startsWith("iris-") }
    }
    val suppliedReference = providers.gradleProperty("iris.experimental.jar")
    val compilerReference = configurations.create("experimentalIrisCompilerReference") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    dependencies {
        add(
            compilerReference.name, if (suppliedReference.isPresent) {
                files(suppliedReference.map { rootProject.file(it) })
            } else {
                "dev.engine_room:iris-fabric-reference:${property("iris_experimental_version")}"
            }
        )
    }
    experimental.compileClasspath += compilerReference
    val neoforgeExperimental = sourceSets.create("neoforgeExperimental") {
        java.setSrcDirs(experimental.java.srcDirs)
        resources.setSrcDirs(experimental.resources.srcDirs)
        java.excludeShadowedSharedSources(sharedModuleFile("src/experimental/java"), file("src/experimental/java"))
        resources.excludeShadowedSharedSources(
            sharedModuleFile("src/experimental/resources"),
            file("src/experimental/resources")
        )
        compileClasspath += neoforgeMain.output
        // NeoForge omits bindings; lower against their Fabric declarations.
        compileClasspath += sourceSets.main.get().output.classesDirs
        compileClasspath += neoforgeMain.compileClasspath.filter { !it.name.startsWith("iris-") }
    }
    neoforgeExperimental.compileClasspath += compilerReference
    configureNeoForgeAccess(neoforgeExperimental)
    neoforgeJar.configure { from(neoforgeExperimental.output) }
    neoforgeAccessSources.add(neoforgeExperimental)
    val referenceRuntime = configurations.create("experimentalIrisReference") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
    artifacts.add(referenceRuntime.name, compilerReference.singleFile)
    tasks.named<Jar>("jar") {
        from(experimental.output)
    }
    tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
        from(experimental.allSource)
    }
}

tenon.accessorMixins(neoforgeJar, *neoforgeAccessSources.toTypedArray())
