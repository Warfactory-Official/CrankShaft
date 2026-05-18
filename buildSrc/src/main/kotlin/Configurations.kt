import com.gtnewhorizons.retrofuturagradle.modutils.ModUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.getByType

val Project.rfg: ModUtils
    get() = extensions.getByType()

fun DependencyHandler.api(notation: Any): Dependency? = add("api", notation)
fun DependencyHandler.api(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("api", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.implementation(notation: Any): Dependency? = add("implementation", notation)
fun DependencyHandler.implementation(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("implementation", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.compileOnly(notation: Any): Dependency? = add("compileOnly", notation)
fun DependencyHandler.compileOnly(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("compileOnly", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.compileOnlyApi(notation: Any): Dependency? = add("compileOnlyApi", notation)
fun DependencyHandler.compileOnlyApi(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("compileOnlyApi", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.runtimeOnly(notation: Any): Dependency? = add("runtimeOnly", notation)
fun DependencyHandler.runtimeOnly(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("runtimeOnly", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.annotationProcessor(notation: Any): Dependency? = add("annotationProcessor", notation)
fun DependencyHandler.annotationProcessor(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("annotationProcessor", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.testImplementation(notation: Any): Dependency? = add("testImplementation", notation)
fun DependencyHandler.testCompileOnly(notation: Any): Dependency? = add("testCompileOnly", notation)
fun DependencyHandler.testRuntimeOnly(notation: Any): Dependency? = add("testRuntimeOnly", notation)
fun DependencyHandler.testAnnotationProcessor(notation: Any): Dependency? = add("testAnnotationProcessor", notation)

fun DependencyHandler.embed(notation: Any): Dependency? = add("embed", notation)
fun DependencyHandler.embed(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("embed", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.devOnlyNonPublishable(notation: Any): Dependency? = add("devOnlyNonPublishable", notation)
fun DependencyHandler.devOnlyNonPublishable(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("devOnlyNonPublishable", notation) as ExternalModuleDependency).apply(configure)

fun DependencyHandler.runtimeOnlyNonPublishable(notation: Any): Dependency? = add("runtimeOnlyNonPublishable", notation)
fun DependencyHandler.runtimeOnlyNonPublishable(notation: String, configure: ExternalModuleDependency.() -> Unit): ExternalModuleDependency =
    (add("runtimeOnlyNonPublishable", notation) as ExternalModuleDependency).apply(configure)
