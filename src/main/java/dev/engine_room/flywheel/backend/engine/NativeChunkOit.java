package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRenderPass;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.util.function.IntSupplier;

public final class NativeChunkOit {
    private static final Long2ObjectOpenHashMap<NativeChunkProgram> PROGRAMS = new Long2ObjectOpenHashMap<>();
    private static Plan plan;
    private static PipelineCompiler.OitMode phase;

    private NativeChunkOit() { }

    public static boolean needed() {
        if (GuestRenderPass.active() || FlwPrograms.chunkOitPrograms() == null) return false;
        // AUSM retains Celeritas's compiler helpers while replacing its frame renderer with vanilla terrain.
        if (Loader.isModLoaded("ausm") && !Loader.isModLoaded("nothirium")) return false;
        if (!Loader.isModLoaded("celeritas") && !Loader.isModLoaded("nothirium")
                && !Loader.isModLoaded("neonium") && !Loader.isModLoaded("vintagium")
                && !Loader.isModLoaded("relictium")) return false;
        var manager = VisualizationManagerImpl.get(Minecraft.getMinecraft().world);
        return manager != null && manager.currentFrameContext() != null && manager.getEngineImpl() != null
                && manager.getEngineImpl().drawManager().hasOitDraws();
    }

    public static Plan begin(IntSupplier draw) {
        var next = new Plan(plan, draw);
        plan = next;
        return next;
    }

    public static boolean active() { return plan != null; }

    public static void replay(PipelineCompiler.OitMode mode) {
        var previous = phase;
        phase = mode;
        try (var state = GlStateTracker.getRestoreState()) {
            try {
                plan.count = plan.draw.getAsInt();
                plan.completed |= 1 << mode.ordinal();
            } finally {
                GlStateTracker.invalidate();
            }
        } finally {
            phase = previous;
            TextureBinder.bindLightAndOverlay();
        }
    }

    public static int bindDraw() {
        if (phase == null) return 0;
        var nativeProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        var key = ((long) nativeProgram << 32) | phase.ordinal();
        var variant = PROGRAMS.get(key);
        if (variant == null) {
            variant = NativeChunkProgram.create(nativeProgram, phase);
            PROGRAMS.put(key, variant);
        }
        variant.bind(nativeProgram);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        var equation = phase == PipelineCompiler.OitMode.DEPTH_RANGE ? GL14.GL_MAX : GL14.GL_FUNC_ADD;
        GL20.glBlendEquationSeparate(equation, equation);
        return nativeProgram;
    }

    public static void restoreDraw(int program) { if (program != 0) GlProgram.bind(program); }

    public static void clear() {
        for (var variant : PROGRAMS.values()) variant.delete();
        PROGRAMS.clear();
    }

    public static final class Plan implements AutoCloseable {
        private final Plan previous;
        private final IntSupplier draw;
        private int count;
        private int completed;

        private Plan(Plan previous, IntSupplier draw) { this.previous = previous; this.draw = draw; }

        public int count() {
            var required = (1 << PipelineCompiler.OitMode.DEPTH_RANGE.ordinal())
                    | (1 << PipelineCompiler.OitMode.GENERATE_COEFFICIENTS.ordinal())
                    | (1 << PipelineCompiler.OitMode.EVALUATE.ordinal());
            if ((completed & required) != required) throw new IllegalStateException("Native terrain OIT did not complete");
            return count;
        }

        @Override public void close() { plan = previous; }
    }
}
