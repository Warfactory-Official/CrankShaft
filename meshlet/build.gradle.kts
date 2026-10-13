plugins {
    id("multiloader-base")
    id("java-library")

    id("net.fabricmc.fabric-loom") version ("1.18.4")
}

base {
    archivesName = "meshlet"
}

val commonMainJava: Configuration = configurations.create("commonMainJava") { isCanBeResolved = true; isCanBeConsumed = false }
val commonApiJava: Configuration = configurations.create("commonApiJava") { isCanBeResolved = true; isCanBeConsumed = false }
val commonLibJava: Configuration = configurations.create("commonLibJava") { isCanBeResolved = true; isCanBeConsumed = false }
val commonBackendJava: Configuration = configurations.create("commonBackendJava") { isCanBeResolved = true; isCanBeConsumed = false }

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
    compileOnly(project(siblingProjectPath("iris")))
    compileOnly("maven.modrinth:iris:${irisVersion}-fabric")

    compileOnly("org.jspecify:jspecify:1.0.0")

    compileOnly("io.github.llamalad7:mixinextras-common:${mixinExtrasVersion}")
    annotationProcessor("io.github.llamalad7:mixinextras-common:${mixinExtrasVersion}")
    compileOnly("net.fabricmc:sponge-mixin:${mixinVersion}")

    compileOnly("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
}

loom {
    mixin {
        useLegacyMixinAp = false
    }
}

tasks {
    jar {
        from(sharedModuleFile("LICENSE")) { into("META-INF") }
        from(sharedModuleFile("THIRD_PARTY_NOTICES")) { into("META-INF") }
    }
}
