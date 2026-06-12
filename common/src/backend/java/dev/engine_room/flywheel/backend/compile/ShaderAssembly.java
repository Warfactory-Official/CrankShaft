package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.core.ShaderCache;
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

    /**
     * Assemble a GLSL 460 source WITHOUT resolving {@code #moj_import} (stages that carry no vanilla import).
     */
    public static String assemble(Consumer<Compilation> preamble, List<SourceComponent> roots) {
        Compilation ctx = new Compilation();
        ctx.version(GlslVersion.V460);
        preamble.accept(ctx);
        ShaderCache.expand(roots, ctx::appendComponent);
        return ctx.assembledSource();
    }

    public static String assembleFlattened(Consumer<Compilation> preamble, List<SourceComponent> roots) {
        return MojImportPreprocessor.flatten(assemble(preamble, roots));
    }

    public record RawSource(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
