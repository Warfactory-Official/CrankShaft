val versionNodes = allprojects.filter { it.parent?.parent == rootProject }

tasks.register("build") {
    group = "build"
    description = "Builds every supported Minecraft version and loader."
    dependsOn(versionNodes.map { "${it.path}:build" })
}

tasks.register("publishAllVersions") {
    group = "publishing"
    description = "Publishes both loader artifacts for every supported Minecraft version."
    dependsOn(versionNodes.filter { it.parent!!.name in listOf("fabric", "neoforge") }
            .map { "${it.path}:publishMavenJarPublicationToWarfactoryRepository" })
}
