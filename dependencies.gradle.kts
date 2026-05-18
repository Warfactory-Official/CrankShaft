dependencies {
    // Cleanroom shades its API
//    compileOnly("zone.rong:mixinbooter:10.7")
    annotationProcessor("zone.rong:mixinbooter:10.7")

    annotationProcessor("org.ow2.asm:asm:9.9")
    annotationProcessor("org.ow2.asm:asm-commons:9.9")
    annotationProcessor("org.ow2.asm:asm-tree:9.9")
    annotationProcessor("org.ow2.asm:asm-util:9.9")
    annotationProcessor("org.ow2.asm:asm-analysis:9.9")
    annotationProcessor("com.google.guava:guava:31.0.1-jre")
    annotationProcessor("com.google.code.gson:gson:2.8.9")

    // org.lwjgl.util.vector.Vector3f
    compileOnly("org.lwjgl.lwjgl:lwjgl_util:2.9.1") { isTransitive = false }
    compileOnly("org.jetbrains:annotations:26.0.2")
}
