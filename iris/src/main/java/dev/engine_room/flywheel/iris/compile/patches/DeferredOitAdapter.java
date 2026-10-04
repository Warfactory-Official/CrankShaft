package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.engine.DeferredReplayPlan;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration of one checked deferred material ABI. Source staging/validation runs during pack loading without
 * GL access; implementations are stateless and shared across dimensions. Renderer plans are built on the render
 * thread and own their per-renderer resources. Generated stages and storage properties must avoid pack-owned
 * slots. {@code storageBindings} names only adapter-owned buffers, removed with generated compute stages on
 * rejection. {@code capturesTerrain} opts terrain into packed-material capture; light-only integrations leave
 * terrain ownership with the pack. Native rendering remains available after rejection.
 */
public interface DeferredOitAdapter {
    void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added);

    String storageProperties();

    int[] storageBindings();

    boolean capturesTerrain();

    Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts);

    void rejected(ProgramSet programs);

    /**
     * Null for unrelated renderer phases; a plan uses ordered identities from this renderer's pass list.
     */
    @Nullable DeferredReplayPlan replayPlan(IrisRenderingPipeline pipeline, List<?> passes);
}
