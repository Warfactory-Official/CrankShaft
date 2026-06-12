package dev.engine_room.flywheel.backend.compile.core;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlShader;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.backend.glsl.GlslProfile;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.glsl.SourceFile;
import dev.engine_room.flywheel.lib.util.StringUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.File;
import java.io.FileWriter;
import java.util.*;

/**
 * Builder style class for compiling shaders.
 * <p>
 * Keeps track of the source files and components used to compile a shader,
 * and interprets/pretty prints any errors that occur.
 */
public class Compilation {
    public static final boolean DUMP_SHADER_SOURCE = System.getProperty("flw.dumpShaderSource") != null;

    private final List<SourceFile> files = new ArrayList<>();
    private final StringBuilder generatedSource = new StringBuilder();
    private final Set<String> extensions = new LinkedHashSet<>();
    private final StringBuilder defines = new StringBuilder();
    private final Set<String> mojImports = new LinkedHashSet<>();
    private final StringBuilder components = new StringBuilder();
    private String versionLine = "";
    private int generatedLines = 0;

    private static void dumpSource(String source, String fileName) {
        if (!DUMP_SHADER_SOURCE) {
            return;
        }

        File file = new File(new File(Minecraft.getInstance().gameDirectory, "flywheel_sources"), fileName);
        // mkdirs of the parent so we don't create a directory named by the leaf file we want to write
        file.getParentFile()
            .mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(source);
        } catch (Exception e) {
            FlwPrograms.LOGGER.error("Could not dump source.", e);
        }
    }

    public static boolean compiledSuccessfully(int handle) {
        return GL20.glGetShaderi(handle, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE;
    }

    public ShaderResult compile(ShaderType shaderType, String name) {
        int handle = GL20.glCreateShader(shaderType.glEnum);
        var source = assembledSource();

        GlCompat.safeShaderSource(handle, source);
        GL20.glCompileShader(handle);

        var shaderName = name + "." + shaderType.extension;
        dumpSource(source, shaderName);

        var infoLog = GL20.glGetShaderInfoLog(handle);

        if (compiledSuccessfully(handle)) {
            return ShaderResult.success(new GlShader(handle, shaderType, shaderName), infoLog);
        }

        GL20.glDeleteShader(handle);
        return ShaderResult.failure(
                new FailedCompilation(shaderName, files, generatedSource.toString(), source, infoLog));
    }

    public String assembledSource() {
        StringBuilder out = new StringBuilder();
        out.append(versionLine);
        for (String ext : extensions) {
            out.append(ext)
               .append('\n');
        }
        out.append(defines);
        for (String moj : mojImports) {
            out.append(moj)
               .append('\n');
        }
        out.append(components);
        return out.toString();
    }

    public void version(GlslVersion version) {
        version(version, GlslProfile.CORE);
    }

    public void version(GlslVersion version, GlslProfile profile) {
        versionLine = "#version " + version.version + (profile.token.isEmpty() ? "" : " " + profile.token) + "\n";
    }

    public void enableExtension(String ext) {
        extensions.add("#extension " + ext + " : enable");
    }

    public void requireExtension(String ext) {
        extensions.add("#extension " + ext + " : require");
    }

    public void define(String key, String value) {
        defines.append("#define ")
               .append(key)
               .append(' ')
               .append(value)
               .append('\n');
    }

    public void define(String key) {
        defines.append("#define ")
               .append(key)
               .append('\n');
    }

    public void mojImport(String module) {
        mojImports.add("#moj_import <" + module + ">");
    }

    public void polyfillFmaIfMissing() {
        polyfillFmaIfMissing(List.of());
    }

    public void polyfillFmaIfMissing(Collection<String> enabledExtensions) {
        if (GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V400) < 0
                && !enabledExtensions.contains("GL_ARB_gpu_shader5")) {
            define("fma(a, b, c) ((a) * (b) + (c))");
        }
    }

    public void appendComponent(SourceComponent component) {
        var source = component.source();

        if (source.contains("#extension")) {
            StringBuilder body = new StringBuilder();
            int conditionalDepth = 0;
            for (String line : source.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#extension")) {
                    if (conditionalDepth > 0) {
                        throw new IllegalStateException("Conditional #extension in shader body " + component.name()
                                + " ('" + trimmed + "'): declare per-variant extensions in Java via "
                                + "Compilation.requireExtension, not inside a #ifdef.");
                    }
                    int comment = trimmed.indexOf("//");
                    extensions.add((comment >= 0 ? trimmed.substring(0, comment) : trimmed).strip());
                } else {
                    body.append(line)
                        .append('\n');
                }
                if (trimmed.startsWith("#if")) {
                    conditionalDepth++;
                } else if (trimmed.startsWith("#endif") && conditionalDepth > 0) {
                    conditionalDepth--;
                }
            }
            source = body.toString();
        }

        appendHeader(component, source);

        components.append(source);
    }

    private void appendHeader(SourceComponent component, String source) {
        if (component instanceof SourceFile file) {
            int fileId = files.size() + 1;

            files.add(file);

            components.append("\n#line 0 ")
                      .append(fileId)
                      .append(" // ")
                      .append(file.name())
                      .append('\n');
        } else {
            // Add extra newline to keep line numbers consistent
            generatedSource.append(source)
                           .append('\n');

            components.append("\n#line ")
                      .append(generatedLines)
                      .append(" 0 // (generated) ") // all generated code is put in file 0
                      .append(component.name())
                      .append('\n');

            generatedLines += StringUtil.countLines(source);
        }
    }
}
