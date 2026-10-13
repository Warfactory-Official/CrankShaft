// Build logic can develop the plugin from the same compiler checkout selected by the main build.
providers.gradleProperty("tenon.source").orNull?.let { source ->
    includeBuild(rootDir.parentFile.resolve(source)) {
        dependencySubstitution {
            substitute(module("mov.movblock.tenon:tenon-gradle-support")).using(project(":gradle-support"))
        }
    }
}
