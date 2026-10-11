package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import dev.engine_room.flywheel.api.lod.LodSurface;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public final class DistantHorizonsCompat {
    public static final boolean ACTIVE = CompatMod.DISTANT_HORIZONS.isLoaded;

    private DistantHorizonsCompat() {
    }

    public static void init() {
        if (ACTIVE) {
            Internals.bind();
        }
    }

    public static int distance() {
        return ACTIVE ? Internals.distance() : 0;
    }

    public static CompletableFuture<LodSurface> surface(ClientLevel level, Heightmap.Types type, int minX, int minZ,
                                                        int size) {
        return Internals.surface(level, type.isOpaque(), minX, minZ, size);
    }

    private static final class Internals {
        private static final int SECTION = 64;
        // Compat with DH 3.3.x: reads run on the calling thread (DhApiTerrainDataRepoMixin), and the repository keeps a
        // connection per thread until the level closes => fixed threads.
        private static final ExecutorService READS = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().daemon().name("CrankShaft LoD read ", 0).factory());

        private static @Nullable DhApiRenderParam applied;
        private static @Nullable GlTextureView glDepth;

        static void bind() {
            DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                    applied = event.isEventCanceled() ? null : event.value;
                }
            });
            DhApi.events.bind(DhApiAfterRenderEvent.class, new DhApiAfterRenderEvent() {
                @Override
                public void afterRender(DhApiEventParam<Void> event) {
                    DhApiRenderParam param = applied;
                    applied = null;
                    if (param != null && !ShadersModHelper.isShaderPackInUse()) {
                        write(param);
                    }
                }
            });
        }

        static int distance() {
            IDhApiConfig configs = DhApi.Delayed.configs;
            if (configs == null) {
                return 0;
            }
            IDhApiGraphicsConfig graphics = configs.graphics();
            return graphics.renderingEnabled().getValue()
                    && graphics.renderingMode().getValue() == EDhApiRendererMode.DEFAULT
                    ? graphics.chunkRenderDistance().getValue() * 16 : 0;
        }

        static CompletableFuture<LodSurface> surface(ClientLevel level, Predicate<BlockState> opaque, int minX,
                                                     int minZ, int size) {
            IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
            IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
            IDhApiLevelWrapper wrapper = world != null && repo != null && world.worldLoaded()
                    ? levelWrapper(world, level) : null;
            if (wrapper == null) {
                return CompletableFuture.completedFuture(new LodSurface(minX, minZ, size, null));
            }
            return CompletableFuture.supplyAsync(() -> new LodSurface(minX, minZ, size,
                    tops(repo, wrapper, opaque, minX, minZ, size)), READS);
        }

        private static @Nullable IDhApiLevelWrapper levelWrapper(IDhApiWorldProxy world, ClientLevel level) {
            String dimension = level.dimension().identifier().toString();
            IDhApiLevelWrapper named = null;
            for (IDhApiLevelWrapper wrapper : world.getAllLoadedLevelWrappers()) {
                if (wrapper.getWrappedMcObject() == level) {
                    return wrapper;
                }
                if (named == null && wrapper.getDimensionName().equals(dimension)) {
                    named = wrapper;
                }
            }
            return named;
        }

        private static int[] tops(IDhApiTerrainDataRepo repo, IDhApiLevelWrapper wrapper, Predicate<BlockState> opaque,
                                  int minX, int minZ, int size) {
            int[] tops = new int[size * size];
            Arrays.fill(tops, LodSurface.UNKNOWN);
            try (IDhApiTerrainDataCache cache = repo.createSoftCache()) {
                for (int sz = Math.floorDiv(minZ, SECTION); sz <= Math.floorDiv(minZ + size - 1, SECTION); sz++) {
                    for (int sx = Math.floorDiv(minX, SECTION); sx <= Math.floorDiv(minX + size - 1, SECTION); sx++) {
                        section(repo, wrapper, cache, opaque, tops, minX, minZ, size, sx, sz);
                    }
                }
            }
            return tops;
        }

        private static void section(IDhApiTerrainDataRepo repo, IDhApiLevelWrapper wrapper, IDhApiTerrainDataCache cache,
                                    Predicate<BlockState> opaque, int[] tops, int minX, int minZ, int size, int sx,
                                    int sz) {
            int x0 = Math.max(minX, sx * SECTION), x1 = Math.min(minX + size, (sx + 1) * SECTION);
            int z0 = Math.max(minZ, sz * SECTION), z1 = Math.min(minZ + size, (sz + 1) * SECTION);
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    DhApiResult<DhApiTerrainDataPoint[]> column = repo.getColumnDataAtBlockPos(wrapper, x, z, cache);
                    if (!column.success) {
                        return;
                    }
                    for (DhApiTerrainDataPoint point : column.payload) {
                        BlockState state = (BlockState) point.blockStateWrapper.getWrappedMcObject();
                        if (state != null && opaque.test(state)) {
                            tops[(z - minZ) * size + x - minX] = point.topYBlockPos;
                            break;
                        }
                    }
                }
            }
        }

        private static void write(DhApiRenderParam param) {
            IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
            Matrix4f mcViewProjection = DistantHorizonsFade.joml(param.mcProjectionMatrix)
                                                           .mul(DistantHorizonsFade.joml(param.mcModelViewMatrix));
            LodDepthPass.draw(depthView(proxy), DistantHorizonsFade.joml(param.dhInverseMvmProjectionMatrix),
                    mcViewProjection,
                    proxy.getDepthDirection().farDepth, proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE);
        }

        private static GpuTextureView depthView(IDhApiRenderProxy proxy) {
            if (proxy.getRenderingEngine() == EDhApiRenderingEngine.BLAZE_3D) {
                return (GpuTextureView) ((Object[]) payload(proxy.getDhDepthTextureBlazeWrapper())
                        .getWrappedMcObject())[1];
            }
            int id = payload(proxy.getDhDepthTextureGlId());
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GlTextureView view = glDepth;
            if (view == null || view.glId() != id || view.getWidth(0) != main.width
                    || view.getHeight(0) != main.height) {
                view = glDepth = wrapForeign(id, main.width, main.height);
            }
            return view;
        }

        private static GlTextureView wrapForeign(int id, int width, int height) {
            FrameBufferCache unused = new FrameBufferCache();
            GlTexture texture = new GlTexture(GpuTexture.USAGE_TEXTURE_BINDING, "Distant Horizons depth",
                    GpuFormat.D32_FLOAT, width, height, 1, 1, id, unused) {
            };
            return new GlTextureView(texture, 0, 1, unused) {
            };
        }

        private static <T> T payload(DhApiResult<T> result) {
            if (!result.success) {
                throw new IllegalStateException(result.message);
            }
            return result.payload;
        }
    }
}
