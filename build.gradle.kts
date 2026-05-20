import com.gtnewhorizons.retrofuturagradle.MinecraftExtension
import com.gtnewhorizons.retrofuturagradle.mcp.InjectTagsTask
import com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks
import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import java.util.Properties

plugins {
    java
    `java-library`
    base
    idea
    eclipse
    `maven-publish`
    id("com.gtnewhorizons.retrofuturagradle")
}

run {
    listOf("buildscript.properties", "version.properties").forEach { name ->
        val f = file(name)
        if (f.exists()) f.reader().use { r ->
            val p = Properties().apply { load(r) }
            p.forEach { k, v -> project.extra.set(k.toString(), v.toString()) }
        }
    }
}

fun prop(name: String): String = project.property(name).toString()
fun propOrEmpty(name: String): String = project.findProperty(name)?.toString().orEmpty()
fun propBool(name: String, default: Boolean = false): Boolean =
    project.findProperty(name)?.toString()?.toBoolean() ?: default

group = prop("modGroup")
version = prop("modVersion")
base { archivesName.set(prop("modArchivesBaseName")) }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.AZUL)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

sourceSets {
    val api = maybeCreate("api").apply {
        java.setSrcDirs(listOf("src/api/java"))
    }
    named("main") {
        compileClasspath += api.output
    }
    maybeCreate("vanillin").apply {
        java.setSrcDirs(listOf("src/vanillin/java"))
        resources.setSrcDirs(listOf("src/vanillin/resources"))
        val main = sourceSets["main"]
        compileClasspath += api.output + main.output + main.compileClasspath
        runtimeClasspath += main.runtimeClasspath
    }
}

listOf("compileJava", "compileApiJava", "compileVanillinJava").forEach { taskName ->
    tasks.named<JavaCompile>(taskName).configure {
        options.compilerArgs.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    }
}

val embed by configurations.creating
val devOnlyNonPublishable by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = false
}
val runtimeOnlyNonPublishable by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = false
}

configurations {
    named("implementation") { extendsFrom(embed) }
    named("compileOnly") { extendsFrom(devOnlyNonPublishable) }
    named("runtimeOnlyNonPublishable") { extendsFrom(devOnlyNonPublishable) }
    named("runtimeClasspath") { extendsFrom(runtimeOnlyNonPublishable) }
    named("testRuntimeClasspath") { extendsFrom(runtimeOnlyNonPublishable) }

    named("apiCompileOnly") { extendsFrom(configurations["compileOnly"]) }
    named("apiImplementation") { extendsFrom(configurations["implementation"]) }
    named("vanillinCompileOnly") { extendsFrom(configurations["compileOnly"]) }
    named("vanillinImplementation") { extendsFrom(configurations["implementation"]) }

    // Forge leaks org.lwjgl.util.vector.Vector3f. Keep lwjgl_util, drop LWJGL 2
    // core + cleanroom's lwjglx bridge so GL FQNs don't collide with LWJGL 3.
    named("compileClasspath") {
        exclude(group = "org.lwjgl.lwjgl", module = "lwjgl")
        exclude(group = "org.lwjgl.lwjgl", module = "lwjgl-platform")
        exclude(group = "com.cleanroommc", module = "lwjglx")
        resolutionStrategy.force("org.lwjgl.lwjgl:lwjgl_util:2.9.1")
    }
}

val coreModFqn = "${prop("modGroup")}.${prop("coreModClass")}"

configure<MinecraftExtension> {
    mcVersion.set(prop("minecraftVersion"))
    loaderType.set("cleanroom")
    username.set(prop("developmentEnvironmentUserName"))

    injectedTags.put(prop("gradleTokenModId"), prop("modId"))
    injectedTags.put(prop("gradleTokenModName"), prop("modName"))
    injectedTags.put(prop("gradleTokenVersion"), prop("modVersion"))
    injectedTags.put("VANILLIN_ID", prop("vanillinId"))
    injectedTags.put("VANILLIN_NAME", prop("vanillinName"))
    injectedTags.put("VANILLIN_VERSION", prop("vanillinVersion"))

    extraRunJvmArguments.add("-ea:${prop("modGroup")}")
    extraRunJvmArguments.add("-Dterminal.jline=true")
    extraRunJvmArguments.add("-Dfml.coreMods.load=$coreModFqn")
    if (propBool("usesMixins")) {
        extraRunJvmArguments.addAll(
            "-Dmixin.hotSwap=true",
            "-Dmixin.checks.interfaces=true",
            "-Dmixin.debug.export=true",
        )
    }
    val extras = propOrEmpty("additionalJavaArguments")
    if (extras.isNotEmpty()) extraRunJvmArguments.addAll(extras.split(";"))
}

tasks.named<InjectTagsTask>("injectTags") {
    outputClassName.set(prop("generateGradleTokenClass"))
}

run {
    val ats = prop("accessTransformersFile").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val mcpTasks = project.extensions.getByType(MCPTasks::class.java)
    for (at in ats) {
        val f = file("src/main/resources/$at")
        if (!f.exists()) throw GradleException("Missing access transformer file: $f")
        mcpTasks.preDecompATs.from(f)
        mcpTasks.deobfuscationATs.from(f)
    }
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "Cleanroom Maven"
        url = uri("https://maven.cleanroommc.com")
    }
    maven {
        name = "Warfactory Releases"
        url = uri("https://repo.warfactory.co/releases")
    }
    mavenCentral()
    mavenLocal()
}

apply(from = "dependencies.gradle.kts")

val mixinTmpDir = layout.buildDirectory.dir("tmp/mixins")
val mixinRefMap = mixinTmpDir.map { it.file(prop("mixinConfigRefmap")) }
val mixinSrg = mixinTmpDir.map { it.file("mixins.srg") }

tasks.named<JavaCompile>("compileJava").configure {
    outputs.file(mixinSrg).withPropertyName("mixinSrg")
    outputs.file(mixinRefMap).withPropertyName("mixinRefMap")
    doFirst {
        val reobfSrgFile = (tasks.named("reobfJar").get() as ReobfuscatedJar)
            .srg.get().asFile.absolutePath
        mixinTmpDir.get().asFile.mkdirs()
        options.compilerArgs.addAll(
            listOf(
                "-AreobfSrgFile=$reobfSrgFile",
                "-AoutSrgFile=${mixinSrg.get().asFile.absolutePath}",
                "-AoutRefMapFile=${mixinRefMap.get().asFile.absolutePath}",
            )
        )
    }
}

tasks.named<Copy>("processResources").configure {
    from(mixinRefMap)
    dependsOn(tasks.named("compileJava"))

    val mainResourceProps = mapOf(
        "modId" to prop("modId"),
        "modName" to prop("modName"),
        "modVersion" to prop("modVersion"),
        "minecraftVersion" to prop("minecraftVersion"),
    )
    inputs.properties(mainResourceProps)
    filesMatching("mcmod.info") { expand(mainResourceProps) }
}

tasks.named<Copy>("processVanillinResources").configure {
    val vanillinResourceProps = mapOf(
        "modId" to prop("modId"),
        "vanillinId" to prop("vanillinId"),
        "vanillinName" to prop("vanillinName"),
        "vanillinVersion" to prop("vanillinVersion"),
        "minecraftVersion" to prop("minecraftVersion"),
    )
    inputs.properties(vanillinResourceProps)
    filesMatching("mcmod.info") { expand(vanillinResourceProps) }
}

tasks.named<ReobfuscatedJar>("reobfJar").configure {
    extraSrgFiles.from(mixinSrg)
    dependsOn(tasks.named("compileJava"))
}

val fmlAtAttribute = prop("accessTransformersFile")
    .split(",")
    .map { it.trim().substringAfterLast('/').substringAfterLast('\\') }
    .filter { it.isNotEmpty() }
    .joinToString(",")

tasks.jar.configure {
    manifest {
        attributes(
            "Specification-Title" to prop("modName"),
            "Specification-Version" to version,
            "Implementation-Title" to prop("modName"),
            "Implementation-Version" to version,
            "FMLCorePlugin" to coreModFqn,
            "FMLCorePluginContainsFMLMod" to "true",
            "FMLAT" to fmlAtAttribute,
        )
    }
}

val vanillinJar = tasks.register<Jar>("vanillinJar") {
    archiveBaseName.set("vanillate")
    archiveVersion.set(prop("vanillinVersion"))
    archiveClassifier.set("dev")
    from(sourceSets["vanillin"].output)
    dependsOn(tasks.named("compileVanillinJava"), tasks.named("processVanillinResources"))
    manifest {
        attributes(
            "Specification-Title" to prop("vanillinName"),
            "Specification-Version" to prop("vanillinVersion"),
            "Implementation-Title" to prop("vanillinName"),
            "Implementation-Version" to prop("vanillinVersion"),
        )
    }
}

// RFG only auto-registers reobfJar for the default jar
val reobfVanillinJar = tasks.register<ReobfuscatedJar>("reobfVanillinJar") {
    val base = tasks.named<ReobfuscatedJar>("reobfJar").get()
    inputJar.set(vanillinJar.flatMap { it.archiveFile })
    dependsOn(vanillinJar)
    mcVersion.set(base.mcVersion)
    srg.set(base.srg)
    fieldCsv.set(base.fieldCsv)
    methodCsv.set(base.methodCsv)
    exceptorCfg.set(base.exceptorCfg)
    recompMcJar.set(base.recompMcJar)
    referenceClasspath.from(base.referenceClasspath)
    archiveBaseName.set("vanillate")
    archiveVersion.set(prop("vanillinVersion"))
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

val vanillinSourcesJar = tasks.register<Jar>("vanillinSourcesJar") {
    archiveBaseName.set("vanillate")
    archiveVersion.set(prop("vanillinVersion"))
    archiveClassifier.set("sources")
    from(sourceSets["vanillin"].allSource)
    manifest {
        attributes(
            "Specification-Title" to prop("vanillinName"),
            "Specification-Version" to prop("vanillinVersion"),
            "Implementation-Title" to prop("vanillinName"),
            "Implementation-Version" to prop("vanillinVersion"),
        )
    }
}

tasks.named("assemble").configure {
    dependsOn(vanillinJar, reobfVanillinJar, vanillinSourcesJar)
}

val hotswapAgent by configurations.creating { isCanBeConsumed = false }
dependencies { hotswapAgent("org.hotswapagent:hotswap-agent:2.0.3") { isTransitive = false } }

val javaToolchains = extensions.getByType<JavaToolchainService>()
val jbrLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

listOf("runClient", "runServer").forEach { runTaskName ->
    tasks.named<JavaExec>(runTaskName) {
        classpath(sourceSets["vanillin"].output)
        doFirst {
            jvmArgs("-javaagent:${hotswapAgent.singleFile.absolutePath}")
            val meta = javaLauncher.get().metadata
            logger.lifecycle("runClient JDK: ${meta.installationPath.asFile.absolutePath} (${meta.vendor})")
            if (meta.vendor.contains("JetBrains", ignoreCase = true)) {
                jvmArgs("-XX:+AllowEnhancedClassRedefinition")
            }
        }
    }
}

afterEvaluate {
    listOf("runClient", "runServer").forEach { runTaskName ->
        tasks.named<JavaExec>(runTaskName) {
            javaLauncher.set(jbrLauncher)
        }
    }
}

val publishGroupId = System.getenv("ARTIFACT_GROUP_ID")
    ?: propOrEmpty("mavenArtifactGroup").ifEmpty { prop("modGroup") }
val publishVersion = System.getenv("RELEASE_VERSION") ?: prop("modVersion")
val publishVanillinVersion = System.getenv("VANILLIN_RELEASE_VERSION") ?: prop("vanillinVersion")
val publishUrl = System.getenv("MAVEN_URL") ?: propOrEmpty("customMavenPublishUrl")

publishing {
    publications {
        create<MavenPublication>("crankshaft") {
            artifact(tasks.named("reobfJar"))
            artifact(tasks.named("sourcesJar"))
            groupId = publishGroupId
            artifactId = prop("modArchivesBaseName")
            version = publishVersion
        }
        create<MavenPublication>("vanillate") {
            artifact(reobfVanillinJar)
            artifact(vanillinSourcesJar)
            groupId = publishGroupId
            artifactId = "vanillate"
            version = publishVanillinVersion
        }
    }
    repositories {
        if (publishUrl.isNotEmpty()) {
            maven {
                name = "warfactory"
                url = uri(publishUrl)
                isAllowInsecureProtocol = !publishUrl.startsWith("https")
                credentials {
                    username = providers.environmentVariable("MAVEN_USER").getOrElse("NONE")
                    password = providers.environmentVariable("MAVEN_PASSWORD").getOrElse("NONE")
                }
            }
        }
    }
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
        inheritOutputDirs = true
    }
}

eclipse {
    classpath {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
