package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import net.minecraft.world.level.LevelAccessor;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Current main-frame opaque terrain, shared by retained and live-instance cullers. Render-thread owned.
 */
public final class SceneOcclusion {
    public static final int AUTO = 0;
    public static final int FORCE_ON = 1;
    public static final int FORCE_OFF = -1;
    private static final long WORK_THRESHOLD = 262_144;
    private static final Set<SceneOcclusion> LIVE = new HashSet<>();
    private static final LevelAttached<SceneOcclusion> LEVELS = new LevelAttached<>(_ -> {
        var scene = new SceneOcclusion();
        LIVE.add(scene);
        return scene;
    }, SceneOcclusion::delete);

    private final Matrix4f projection = new Matrix4f();
    private @Nullable FarOcclusion pyramid;
    private @Nullable IndirectPrograms programs;
    private @Nullable View view;
    private long farWork;
    private int farMode;
    private boolean automatic;
    private long frameEpoch;

    private SceneOcclusion() {
    }

    public static SceneOcclusion of(LevelAccessor level) {
        return LEVELS.get(level);
    }

    public static void unload(LevelAccessor level) {
        LEVELS.remove(level);
    }

    public static void destroyGpuResources() {
        for (var scene : LIVE) scene.deleteGpu();
    }

    /**
     * Starts the main view before either producer or consumer runs; the preceding frame's view expires here.
     */
    public void beginFrame(RenderContext frame) {
        frameEpoch++;
        view = null;
        farWork = 0;
        farMode = AUTO;
        projection.set(frame.projection());
    }

    /**
     * Identifies the current main view for providers whose draw packet is prepared once per frame.
     */
    public long frameEpoch() {
        return frameEpoch;
    }

    /**
     * The retained view's longer depth range uses the same camera lens; both cullers consume this encoding.
     */
    public void projection(Matrix4fc projection) {
        this.projection.set(projection);
    }

    /**
     * Retained preparation publishes its current work and an optional explicit comparison override.
     */
    public void farWork(long work, int mode) {
        farWork = work;
        farMode = mode;
    }

    public boolean requested() {
        long work = farMode == FORCE_OFF ? 0 : farWork;
        automatic = automatic ? work >= WORK_THRESHOLD : work > WORK_THRESHOLD * 2;
        return automatic || farMode == FORCE_ON;
    }

    /**
     * Publishes one current terrain view at the LoD opaque seam. The caller owns the surrounding host GL state.
     * Retained geometry and water have not entered the source depth at this point.
     */
    public @Nullable View capture(FarDepth source) {
        if (view != null || !requested()) return view;
        var current = IndirectPrograms.get();
        if (programs != current) {
            deleteGpu();
            pyramid = new FarOcclusion();
            programs = current;
        }
        if (!pyramid.capture(source, projection)) return null;
        return view = new View(pyramid, source.width(), source.height(), pyramid.levels(),
                new Matrix4f(projection));
    }

    public @Nullable View view() {
        return view;
    }

    private void deleteGpu() {
        view = null;
        if (pyramid != null) {
            pyramid.delete();
            pyramid = null;
        }
        programs = null;
    }

    private void delete() {
        deleteGpu();
        LIVE.remove(this);
    }

    /**
     * The canonical reversed-Z view remains valid until the next main frame or resource destruction.
     */
    public static final class View {
        private final FarOcclusion pyramid;
        private final int width;
        private final int height;
        private final int levels;
        private final Matrix4fc projection;

        private View(FarOcclusion pyramid, int width, int height, int levels, Matrix4fc projection) {
            this.pyramid = pyramid;
            this.width = width;
            this.height = height;
            this.levels = levels;
            this.projection = projection;
        }

        public void bind() {
            pyramid.bind();
        }

        public int texture() {
            return pyramid.texture();
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int levels() {
            return levels;
        }

        /**
         * Supplies the shared texture's exact depth projection and aligned 2×2 mip mapping to a GL culler.
         */
        public void configure(GlProgram program) {
            program.setBool("_flw_occlusionEnabled", true);
            program.setBool("_flw_sharedDepth", true);
            program.setMat4("_flw_sharedProjection", projection);
            program.setVec2("_flw_sharedDepthSize", width, height);
            program.setInt("_flw_sharedDepthLevels", levels);
        }
    }

    /**
     * Cached locations for raw mesh-task programs; supplied whenever that program is selected for a main draw.
     */
    public static final class Uniforms {
        private final int enabled;
        private final int shared;
        private final int projection;
        private final int size;
        private final int levels;

        public Uniforms(int program) {
            enabled = GL20C.glGetUniformLocation(program, "_flw_occlusionEnabled");
            shared = GL20C.glGetUniformLocation(program, "_flw_sharedDepth");
            projection = GL20C.glGetUniformLocation(program, "_flw_sharedProjection");
            size = GL20C.glGetUniformLocation(program, "_flw_sharedDepthSize");
            levels = GL20C.glGetUniformLocation(program, "_flw_sharedDepthLevels");
        }

        public void configure(@Nullable View view, boolean active) {
            GL20C.glUniform1i(enabled, active ? 1 : 0);
            GL20C.glUniform1i(shared, view != null ? 1 : 0);
            if (view != null) {
                GL20C.glUniform2f(size, view.width, view.height);
                GL20C.glUniform1i(levels, view.levels);
                try (var stack = MemoryStack.stackPush()) {
                    GL20C.glUniformMatrix4fv(projection, false, view.projection.get(stack.mallocFloat(16)));
                }
            }
        }
    }
}
