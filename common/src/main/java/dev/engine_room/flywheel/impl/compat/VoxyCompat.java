package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.api.lod.LodSurface;
import dev.engine_room.flywheel.backend.engine.far.FarDepth;
import dev.engine_room.flywheel.impl.far.FarHost;
import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
#if MC >= 26.3
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
#endif
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
#if MC >= 26.3
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
#endif
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class VoxyCompat {
    public static final boolean ACTIVE = CompatMod.VOXY.isLoaded;

    private VoxyCompat() {
    }

    public static void renderInPlaceOfCutout(ChunkRenderMatrices matrices, FogParameters fog, double x, double y,
                                             double z) {
        if (ACTIVE) {
            Internals.renderOpaque(matrices, fog, x, y, z);
        }
    }

    public static int distance() {
        return ACTIVE ? Internals.distance() : 0;
    }

    /**
     * Whether Voxy draws its LoDs into this Iris pipeline's targets and exposes their depth to its programs.
     */
    public static boolean drawsInto(Object irisPipeline) {
        #if MC >= 26.3
        return ACTIVE && Internals.drawsInto(irisPipeline);
        #else
        // 26.2: Voxy 0.2.19 draws no LoDs under a shaderpack.
        return false;
        #endif
    }

    public static boolean staticVisualsAvailable() {
        #if MC >= 26.3
        return ACTIVE && VoxyConfig.CONFIG.isRenderingEnabled() && VoxyStaticVisuals.available();
        #else
        return false;
        #endif
    }

    /**
     * Far visuals into Voxy's opaque targets. Render thread, mid-pipeline (Voxy's GL state).
     */
    public static void drawFarVisuals(NormalRenderPipeline pipeline, int colour, Viewport<?> viewport) {
        Internals.drawFarVisuals(pipeline, colour, viewport);
    }

    /**
     * Supplies completed opaque terrain and actual vanilla depth before either receives retained visuals.
     */
    public static void afterOpaque(AbstractRenderPipeline pipeline, Viewport<?> viewport, int sourceDepth,
                                   int sourceWidth, int sourceHeight) {
        Internals.afterOpaque(pipeline, viewport, sourceDepth, sourceWidth, sourceHeight);
    }

    public static CompletableFuture<LodSurface> surface(ClientLevel level, Heightmap.Types type, int minX, int minZ,
                                                        int size) {
        Predicate<BlockState> opaque = type.isOpaque();
        return CompletableFuture.supplyAsync(() -> new LodSurface(minX, minZ, size,
                Heights.tops(level, opaque, minX, minZ, size)), Util.nonCriticalIoPool());
    }

    private static final class Internals {
        static void afterOpaque(AbstractRenderPipeline pipeline, Viewport<?> viewport, int sourceDepth,
                                int sourceWidth, int sourceHeight) {
            if (IrisUtil.irisShadowActive()) return;
            var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
            var far = manager == null ? null : manager.farVisuals();
            if (far == null) return;
            float uScale = (float) viewport.width / sourceWidth;
            float vScale = (float) viewport.height / sourceHeight;
            #if MC >= 26.3
            if (pipeline instanceof IrisVoxyRenderPipeline iris && !iris._getData().useViewportDims) {
                uScale = vScale = 1.0f;
            }
            #endif
            var properties = pipeline.properties;
            far.afterOpaque(new FarDepth(pipeline.fb.getDepthTex().id, sourceDepth, viewport.width, viewport.height,
                    viewport.projection, viewport.vanillaProjection,
                    properties.isZero2One(), properties.isReverseZ(),
                    properties.isZero2One(), properties.isReverseZ(), uScale, vScale));
        }

        static void drawFarVisuals(NormalRenderPipeline pipeline, int colour, Viewport<?> viewport) {
            VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
            FarVisuals far = manager == null ? null : manager.farVisuals();
            if (far == null || far.size() == 0) {
                return;
            }
            // Voxy's setup leaves stencil 1 where no vanilla terrain was drawn: only there may LoDs draw. The main
            // depth holds vanilla terrain alone until Voxy's final blit.
            far.draw(new FarHost(colour, pipeline.fb.getDepthTex().id, viewport.width, viewport.height,
                    viewport.projection, pipeline.properties.isZero2One(), pipeline.properties.isReverseZ(), 1));
            far.drawMain();
        }

        static int distance() {
            VoxyConfig config = VoxyConfig.CONFIG;
            return config.isRenderingEnabled() ? Math.round(config.sectionRenderDistance * 32 * 16) : 0;
        }

        #if MC >= 26.3
        // Its Iris uniforms register only with rendering and shader support on; its samplers only with the pack's patch.
        static boolean drawsInto(Object irisPipeline) {
            return VoxyConfig.CONFIG.isRenderingEnabled() && IrisUtil.SHADER_SUPPORT
                    && irisPipeline instanceof IGetVoxyPatchData patched && patched.voxy$getPatchData() != null;
        }
        #endif

        static void renderOpaque(ChunkRenderMatrices matrices, FogParameters fog, double x, double y, double z) {
            VoxyRenderSystem renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer == null) {
                return;
            }
            RenderTarget target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
            Viewport<?> viewport;
            if (IrisUtil.USED_IRIS_VIEWPORT) {
                viewport = renderer.getViewport();
                IrisUtil.USED_IRIS_VIEWPORT = false;
            } else {
                viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fog, target.width,
                        target.height, x, y, z);
            }
            renderer.renderOpaque(viewport, ((GlTextureView) target.getDepthTextureView()).glId(),
                    ((GlTextureView) target.getColorTextureView()).glId());
        }
    }

    private static final class Heights {
        private static final int NONE = LodSurface.UNKNOWN;
        private static final byte OPEN = -1;

        private final WorldEngine engine;
        private final Predicate<BlockState> opaque;
        private final int minX, minZ, maxX, maxZ, size, minY, topY;
        private final int[] bound, tops;
        private final byte[] settled;
        private final int[] start = new int[32 * 32];
        private final boolean[] seen = new boolean[32 * 32];
        private boolean[] opaqueById = new boolean[0];

        private Heights(WorldEngine engine, Level level, Predicate<BlockState> opaque, int minX, int minZ, int size) {
            this.engine = engine;
            this.opaque = opaque;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = minX + size - 1;
            this.maxZ = minZ + size - 1;
            this.size = size;
            this.minY = level.getMinY();
            this.topY = level.getMaxY() + 1;
            this.bound = new int[size * size];
            this.tops = new int[size * size];
            this.settled = new byte[size * size];
            Arrays.fill(bound, NONE);
            Arrays.fill(tops, NONE);
            Arrays.fill(settled, OPEN);
        }

        static int @Nullable [] tops(Level level, Predicate<BlockState> opaque, int minX, int minZ, int size) {
            WorldIdentifier id = WorldIdentifier.of(level);
            if (id == null) {
                return null;
            }
            WorldEngine engine;
            try {
                engine = id.getNullable();
                if (engine == null) {
                    return null;
                }
                engine.acquireRef();
            } catch (IllegalStateException _) {
                return null;
            }
            try {
                Heights heights = new Heights(engine, level, opaque, minX, minZ, size);
                if (!heights.pass(WorldEngine.MAX_LOD_LAYER, true)) {
                    return null;
                }
                for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
                    if (!heights.pass(lvl, false)) {
                        return null;
                    }
                }
                return heights.tops;
            } catch (IllegalStateException _) {
                return null;
            } finally {
                if (engine.isLive()) {
                    engine.releaseRef();
                }
            }
        }

        // Level-4 non-air top bounds every finer level: ingest writes all levels together.
        private boolean pass(int lvl, boolean bounding) {
            int shift = lvl + 5;
            int syMin = minY >> shift;
            for (int sx = minX >> shift; sx <= maxX >> shift; sx++) {
                for (int sz = minZ >> shift; sz <= maxZ >> shift; sz++) {
                    int ox = sx << 5, oz = sz << 5;
                    int lx0 = Math.max(minX >> lvl, ox) - ox, lx1 = Math.min(maxX >> lvl, ox + 31) - ox;
                    int lz0 = Math.max(minZ >> lvl, oz) - oz, lz1 = Math.min(maxZ >> lvl, oz + 31) - oz;
                    int open = 0, highest = NONE;
                    for (int lz = lz0; lz <= lz1; lz++) {
                        for (int lx = lx0; lx <= lx1; lx++) {
                            int c = (lz << 5) | lx;
                            int s = startOf(ox + lx, oz + lz, lvl, bounding);
                            start[c] = s;
                            seen[c] = false;
                            if (s != NONE) {
                                open++;
                                highest = Math.max(highest, s);
                            }
                        }
                    }
                    if (open == 0) {
                        continue;
                    }
                    for (int sy = (highest - 1) >> 5; open > 0 && sy >= syMin; sy--) {
                        if (!engine.instanceIn.isRunning()) {
                            return false;
                        }
                        WorldSection section = engine.acquireIfExists(lvl, sx, sy, sz);
                        if (section == null) {
                            continue;
                        }
                        try {
                            long[] data = section._unsafeGetRawDataArray();
                            int base = sy << 5;
                            for (int lz = lz0; lz <= lz1; lz++) {
                                for (int lx = lx0; lx <= lx1; lx++) {
                                    int c = (lz << 5) | lx;
                                    if (start[c] <= base) {
                                        continue;
                                    }
                                    for (int ly = Math.min(31, start[c] - 1 - base); ly >= 0; ly--) {
                                        int block = Mapper.getBlockId(data[WorldSection.getIndex(lx, ly, lz)]);
                                        if (block == 0) {
                                            continue;
                                        }
                                        seen[c] = true;
                                        if (bounding || opaque(block)) {
                                            settle(ox + lx, oz + lz, lvl, bounding, (base + ly + 1) << lvl);
                                            start[c] = NONE;
                                            open--;
                                            break;
                                        }
                                    }
                                }
                            }
                        } finally {
                            section.release();
                        }
                    }
                    if (!bounding) {
                        for (int lz = lz0; lz <= lz1; lz++) {
                            for (int lx = lx0; lx <= lx1; lx++) {
                                int c = (lz << 5) | lx;
                                if (start[c] != NONE && seen[c]) {
                                    settle(ox + lx, oz + lz, lvl, false, minY);
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }

        private int startOf(int vx, int vz, int lvl, boolean bounding) {
            int x1 = Math.min(maxX, ((vx + 1) << lvl) - 1), z1 = Math.min(maxZ, ((vz + 1) << lvl) - 1);
            for (int z = Math.max(minZ, vz << lvl); z <= z1; z++) {
                for (int x = Math.max(minX, vx << lvl); x <= x1; x++) {
                    int i = (z - minZ) * size + x - minX;
                    if (bounding) {
                        if (bound[i] == NONE) {
                            return (topY + (1 << lvl) - 1) >> lvl;
                        }
                    } else if (settled[i] == OPEN && bound[i] != NONE) {
                        return bound[i] >> lvl;
                    }
                }
            }
            return NONE;
        }

        private void settle(int vx, int vz, int lvl, boolean bounding, int top) {
            int x1 = Math.min(maxX, ((vx + 1) << lvl) - 1), z1 = Math.min(maxZ, ((vz + 1) << lvl) - 1);
            for (int z = Math.max(minZ, vz << lvl); z <= z1; z++) {
                for (int x = Math.max(minX, vx << lvl); x <= x1; x++) {
                    int i = (z - minZ) * size + x - minX;
                    if (bounding) {
                        if (bound[i] == NONE) {
                            bound[i] = top;
                        }
                    } else if (settled[i] == OPEN && bound[i] != NONE) {
                        tops[i] = top;
                        settled[i] = (byte) lvl;
                    }
                }
            }
        }

        private boolean opaque(int block) {
            if (block >= opaqueById.length) {
                Mapper.StateEntry[] entries = engine.getMapper().getStateEntries();
                opaqueById = new boolean[entries.length];
                for (Mapper.StateEntry entry : entries) {
                    opaqueById[entry.id] = opaque.test(entry.state);
                }
            }
            return opaqueById[block];
        }
    }
}
