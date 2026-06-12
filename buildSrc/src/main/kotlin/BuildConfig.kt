import org.gradle.api.Project

private fun Project.stringProp(name: String) = property(name) as String

val Project.minecraftVersion: String get() = stringProp("minecraft_version")
val Project.neoVersion: String get() = stringProp("neo_version")
val Project.fabricLoaderVersion: String get() = stringProp("fabric_loader_version")
val Project.fabricApiVersion: String get() = stringProp("fabric_api_version")
val Project.sodiumVersion: String get() = stringProp("sodium_version")
val Project.modVersion: String get() = stringProp("mod_version")

val Project.minecraftVersionShort: String
    get() = minecraftVersion.replace("-snapshot-", "s").replace("-pre-", "p").replace("-rc-", "r")

val Project.versionString: String
    get() {
        val release = hasProperty("build.release")
        val base = if (release) modVersion else modVersion.substringBefore('-')
        return "$base+mc$minecraftVersionShort" + if (release) "" else "-SNAPSHOT"
    }
