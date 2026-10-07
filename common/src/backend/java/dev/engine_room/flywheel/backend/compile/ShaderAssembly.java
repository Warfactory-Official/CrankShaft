package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.core.ShaderCache;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public final class ShaderAssembly {
    public static final Consumer<Compilation> NO_EXTRA = ctx -> {
    };

    private ShaderAssembly() {
    }

    public static GlslVersion glslVersion() {
        return RenderSystem.getDevice().backend instanceof GlDevice ? GlCompat.MAX_GLSL_VERSION : GlslVersion.V460;
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
        return MojImportPreprocessor.flatten(assemble(version, preamble, roots));
    }

    public record RawSource(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
