package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.core.ShaderCache;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.vk.VkContext;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public final class ShaderAssembly {
    public static final Consumer<Compilation> NO_EXTRA = ctx -> {
    };

    private ShaderAssembly() {
    }

    public static GlslVersion glslVersion() {
        return VkContext.isVulkanHost() ? GlslVersion.V460 : GlCompat.MAX_GLSL_VERSION;
    }

    public static String assemble(Consumer<Compilation> preamble, List<SourceComponent> roots) {
        return assemble(glslVersion(), preamble, roots);
    }

    public static String assemble(GlslVersion version, Consumer<Compilation> preamble, List<SourceComponent> roots) {
        Compilation ctx = new Compilation();
        ctx.version(version);
        if (version.compareTo(GlslVersion.V400) < 0) {
            ctx.define("fma(a, b, c) ((a) * (b) + (c))");
        }
        preamble.accept(ctx);
        ShaderCache.expand(roots, ctx::appendComponent);
        return ctx.assembledSource();
    }

    public static String assembleFlattened(Consumer<Compilation> preamble, List<SourceComponent> roots) {
        return assembleFlattened(glslVersion(), preamble, roots);
    }

    public static String assembleFlattened(GlslVersion version, Consumer<Compilation> preamble,
                                           List<SourceComponent> roots) {
        #if MC >= 26.3
        return assemble(version, preamble, roots);
        #else
        return MojImportPreprocessor.flatten(assemble(version, preamble, roots));
        #endif
    }

    public static void appendComponents(Compilation compilation, List<SourceComponent> roots) {
        ShaderCache.expand(roots, compilation::appendComponent);
    }

    public record RawSource(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
