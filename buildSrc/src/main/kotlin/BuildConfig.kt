import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

private fun Project.stringProp(name: String) = property(name) as String

val Project.minecraftVersion: String get() = stringProp("minecraft_version")
val Project.neoVersion: String get() = stringProp("neo_version")
val Project.fabricLoaderVersion: String get() = stringProp("fabric_loader_version")
val Project.fabricApiVersion: String get() = stringProp("fabric_api_version")
val Project.sodiumVersion: String get() = stringProp("sodium_version")
val Project.irisVersion: String get() = stringProp("iris_version")
val Project.mixinVersion: String get() = stringProp("mixin_version")
val Project.mixinExtrasVersion: String get() = stringProp("mixinextras_version")
val Project.glslTransformerVersion: String get() = stringProp("glsl_transformer_version")
val Project.antlrRuntimeVersion: String get() = stringProp("antlr_runtime_version")
val Project.voxyCompileVersion: String get() = stringProp("voxy_compile_version")
val Project.distantHorizonsVersion: String get() = stringProp("distant_horizons_version")
val Project.entityCullingVersion: String get() = stringProp("entityculling_version")
val Project.lambDynLightsVersion: String get() = stringProp("lambdynamiclights_version")
val Project.etfVersion: String get() = stringProp("entity_texture_features_version")
val Project.emfVersion: String get() = stringProp("entity_model_features_version")
val Project.polytoneCompileVersion: String get() = stringProp("polytone_compile_version")
val Project.vitrailVersion: String get() = stringProp("vitrail_version")
val Project.modVersion: String get() = stringProp("mod_version")
val Project.tenonVersion: String get() = stringProp("tenon_version")

fun Project.siblingProjectPath(module: String): String = ":$module:$minecraftVersion"

fun Project.sharedModuleFile(path: String): File = parent!!.file(path)

fun Project.versionedModuleFile(path: String): File = file(path).takeIf(File::exists) ?: sharedModuleFile(path)

/** Sodium's NeoForge implementation is nested separately from its API. */
fun Project.neoforgeSodiumImplementation(): FileCollection {
    val reference = configurations.create("neoforgeSodiumReference") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add(reference.name, "net.caffeinemc:sodium-neoforge:$sodiumVersion")
    return files(provider {
        zipTree(reference.singleFile).matching { include("META-INF/jarjar/*-mod.jar") }.singleFile
    })
}

/** Compile-only libraries nested by an optional mod. */
fun Project.nestedModLibraries(notation: String, vararg names: String): FileCollection {
    val reference = configurations.detachedConfiguration(dependencies.create(notation)).apply { isTransitive = false }
    return files(provider {
        zipTree(reference.singleFile).matching { names.forEach { include("META-INF/jars/$it-*.jar") } }.files
    })
}

private const val MIXIN_CONFIG_SUFFIX = ".mixins.json"

fun SourceDirectorySet.excludeShadowedSharedSources(sharedRoot: File, localRoot: File) {
    val sharedPath = sharedRoot.toPath().toAbsolutePath().normalize()
    val localPath = localRoot.toPath().toAbsolutePath().normalize()
    exclude { entry ->
        val sourcePath = entry.file.toPath().toAbsolutePath().normalize()
        when {
            entry.isDirectory -> false
            // Merged by mergeMixinConfigs instead of shadowing.
            entry.name.endsWith(MIXIN_CONFIG_SUFFIX) -> sourcePath.startsWith(localPath)
                    && sharedPath.resolve(localPath.relativize(sourcePath)).toFile().isFile

            else -> sourcePath.startsWith(sharedPath)
                    && localPath.resolve(sharedPath.relativize(sourcePath)).toFile().isFile
        }
    }
}

// <module>/versions/<version>/src/<set>/resources -> <module>/src/<set>/resources
private fun File.sharedResourceRoot(): File? {
    val src = parentFile?.parentFile ?: return null
    val versions = src.parentFile?.parentFile ?: return null
    if (name != "resources" || src.name != "src" || versions.name != "versions") return null
    return versions.parentFile.resolve("src/${parentFile.name}/resources")
}

// Nested sets included: loader suites consume common's resource sets.
private fun SourceDirectorySet.mixinConfigFragments(): List<Pair<File, String>> = srcDirs.flatMap { root ->
    val shared = root.sharedResourceRoot() ?: return@flatMap emptyList()
    root.walk().filter { it.isFile && it.name.endsWith(MIXIN_CONFIG_SUFFIX) }
        .map { it to it.relativeTo(root).path }
        .filter { (_, path) -> shared.resolve(path).isFile }
        .toList()
}

/** A version mixin config beside a shared one lists only version entries, merged in sorted position. */
fun ProcessResources.mergeMixinConfigs(resources: SourceDirectorySet) {
    inputs.files(project.provider { resources.mixinConfigFragments().map { it.first } })
        .withPropertyName("mixinConfigFragments")
    doLast {
        for ((fragment, path) in resources.mixinConfigFragments()) {
            val output = destinationDir.resolve(path)

            @Suppress("UNCHECKED_CAST")
            val config = JsonSlurper().parse(output) as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            for ((key, entries) in JsonSlurper().parse(fragment) as Map<String, List<String>>) {
                @Suppress("UNCHECKED_CAST")
                val shared = config[key] as List<String>? ?: emptyList()
                check(entries.none(shared::contains)) { "$fragment repeats shared $key entries" }
                val merged = ArrayList<String>(shared.size + entries.size)
                var next = 0
                for (entry in entries.sorted()) {
                    while (next < shared.size && shared[next] < entry) merged += shared[next++]
                    merged += entry
                }
                config[key] = merged + shared.drop(next)
            }
            output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(config)) + "\n")
        }
    }
}

fun Project.aliasSourceRoots(sourceSet: String): List<File> {
    val module = parent!!.name
    val layers = when {
        sourceSet == "api" -> emptyList()
        sourceSet == "lib" || sourceSet == "vanillin"
                || module in listOf("vanillinFabric", "vanillinNeoForge") -> listOf("lib")

        else -> listOf("lib", "backend")
    }
    return layers.map { rootProject.file("common/src/${it}Aliases/java") }
}

val Project.modMetadataProperties: Map<String, String>
    get() = listOf(
        "minecraft_fabric_range", "minecraft_neoforge_range", "neoforge_range", "fabric_loader_range",
        "fabric_api_range", "sodium_fabric_range", "sodium_minimum", "sodium_maximum", "sodium_neoforge_range",
        "iris_minimum", "iris_neoforge_range", "resource_pack_format_major", "resource_pack_format_minor"
    ).associateWith { stringProp(it) } + ("version" to version.toString())

val Project.minecraftVersionShort: String
    get() = minecraftVersion.replace("-snapshot-", "s").replace("-pre-", "p").replace("-rc-", "r")

val Project.versionString: String
    get() {
        val release = hasProperty("build.release")
        val base = if (release) modVersion else modVersion.substringBefore('-')
        return "$base+mc$minecraftVersionShort" + if (release) "" else "-SNAPSHOT"
    }
