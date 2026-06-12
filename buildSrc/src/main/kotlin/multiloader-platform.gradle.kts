plugins {
    id("multiloader-base")
    id("maven-publish")
}

publishing {
    repositories {
        maven {
            name = "warfactory"
            url = uri(System.getenv("MAVEN_URL") ?: "https://repo.warfactory.co/releases")
            credentials {
                username = System.getenv("MAVEN_USER")
                password = System.getenv("MAVEN_PASSWORD")
            }
        }
    }
}

tasks {
    processResources {
        inputs.property("version", version)

        filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "pack.mcmeta")) {
            expand(mapOf("version" to inputs.properties["version"]))
        }
    }
}
