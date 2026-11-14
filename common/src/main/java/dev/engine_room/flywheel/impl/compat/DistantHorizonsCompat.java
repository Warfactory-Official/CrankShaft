package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.FrameBufferCache;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
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
import com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeVanillaFadeRenderer;
import com.seibel.distanthorizons.core.config.eventHandlers.RenderBlockCacheCsvHandler;
import dev.engine_room.flywheel.api.lod.LodSurface;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
#endif
import dev.engine_room.flywheel.impl.far.FarHost;
import dev.engine_room.flywheel.backend.engine.far.FarDepth;
import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
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
    private static Runnable opaque = () -> {
    };
    #if MC >= 26.3
    private static boolean solidTerrainPhase;
    #endif

    private DistantHorizonsCompat() {
    }

    public static void init() {
        if (ACTIVE) {
            #if MC >= 26.3
            ShaderWarmup.registerNativeResources(LodCompat::warmGpuResources);
            #endif
            DhStaticVisuals.registerNative(new DhStaticDrawProvider());
            Internals.bind();
        }
    }

    public static int distance() {
        return ACTIVE ? Internals.distance() : 0;
    }

    /**
     * DH's terrain material category for a retained block surface, including registered DH material overrides.
     */
    public static int material(BlockState state) {
        return ACTIVE ? Internals.material(state) : 0;
    }

    /**
     * DH's cached render-ignore set excludes containers while the exact static host is available.
     */
    public static boolean ignoreStaticContainers() {
        return ACTIVE && BackendManagerImpl.isBackendOn() && FarVisuals.available();
    }

    /**
     * On render-thread backend ON/OFF transitions, rebuilds native terrain with the current container exclusions.
     */
    public static void refreshStaticContainers() {
        if (ACTIVE && FarVisuals.available()) Internals.refreshStaticContainers();
    }

    /**
     * Registers the shaderpack draw that follows the retained renderer's opaque-depth cull, on the render thread.
     */
    public static void registerOpaque(Runnable draw) {
        opaque = draw;
    }

    #if MC >= 26.3
    /**
     * Marks vanilla's solid execution on the render thread; DH's 26.3 render belongs to prepareTranslucents.
     */
    public static void solidTerrainPhase(boolean active) {
        solidTerrainPhase = active;
    }

    /**
     * Whether DH's obsolete opaque-layer invocation must be skipped, before its render-thread tasks run.
     */
    public static boolean solidTerrainPhase() {
        return solidTerrainPhase;
    }
    #endif

    /**
     * DH's opaque terrain is complete; retained surfaces, generic objects and water have not drawn.
     */
    public static void afterOpaque(DhApiRenderParam param) {
        if (!GuestTerrainGate.renderingShadow()) Internals.afterOpaque(param);
    }

    /**
     * DH's current opaque colour/depth attachments and view, borrowed by direct static geometry.
     */
    public static FarHost farHost(DhApiRenderParam param) {
        return Internals.farHost(param);
    }

    static void destroyGpuResources() {
        if (ACTIVE) {
            DhStaticVisuals.destroyGpuResources();
            Internals.destroyGpuResources();
        }
    }

    static void releaseFadeMaskView(GpuTexture texture) {
        if (ACTIVE) {
            Internals.releaseFadeMaskView(texture);
        }
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

        static void refreshStaticContainers() {
            RenderBlockCacheCsvHandler.INSTANCE.onConfigTimeout();
        }

        static void destroyGpuResources() {
            if (glDepth != null) {
                glDepth.close();
                glDepth = null;
            }
        }

        static void releaseFadeMaskView(GpuTexture texture) {
            if (DhApi.Delayed.renderProxy.getRenderingEngine() != EDhApiRenderingEngine.BLAZE_3D) {
                return;
            }
            // Compat with DH 3.3.4: its fade wrapper creates an extra view/sampler but has no shutdown release.
            var wrapper = BlazeVanillaFadeRenderer.INSTANCE.mcDepthTextureWrapper;
            var view = wrapper.getTextureView();
            if (view != null && view.texture() == texture) {
                view.close();
                wrapper.getTextureSampler().close();
            }
        }

        static void bind() {
            DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new DhApiBeforeApplyShaderRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                    applied = event.isEventCanceled() ? null : event.value;
                }
            });
            DhApi.events.bind(DhApiAfterRenderEvent.class, new DhApiAfterRenderEvent() {
                @Override
                public void afterRender(DhApiEventParam<Void> _) {
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

        static int material(BlockState state) {
            var level = levelWrapper(DhApi.Delayed.worldProxy, Minecraft.getInstance().level);
            return DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{state}, level).getMaterialId();
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

        private static void section(IDhApiTerrainDataRepo repo, IDhApiLevelWrapper wrapper,
                                    IDhApiTerrainDataCache cache,
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

        private static void afterOpaque(DhApiRenderParam param) {
            var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
            var far = manager == null ? null : manager.farVisuals();
            if (far == null) return;
            IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int terrainDepth = proxy.getRenderingEngine() == EDhApiRenderingEngine.BLAZE_3D
                    ? glId(proxy.getDhDepthTextureBlazeWrapper()) : payload(proxy.getDhDepthTextureGlId());
            Matrix4f terrainProjection = DistantHorizonsFade.joml(param.dhProjectionMatrix)
                                                            .mul(DistantHorizonsFade.joml(param.dhModelViewMatrix))
                                                            .mul(DistantHorizonsFade.joml(param.mcModelViewMatrix)
                                                                                    .invert());
            far.afterOpaque(new FarDepth(terrainDepth, 0,
                    main.width, main.height, terrainProjection, DistantHorizonsFade.joml(param.mcProjectionMatrix),
                    proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE,
                    proxy.getDepthDirection().farDepth < 0.5f,
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), true,
                    (float) FrameUniforms.renderExtent(main.width) / main.width,
                    (float) FrameUniforms.renderExtent(main.height) / main.height));
            if (ShadersModHelper.isShaderPackInUse()) {
                opaque.run();
            } else {
                drawFarVisuals(param);
            }
        }

        private static void drawFarVisuals(DhApiRenderParam param) {
            VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
            FarVisuals far = manager == null ? null : manager.farVisuals();
            if (far == null || far.size() == 0) {
                return;
            }
            far.draw(farHost(param));
        }

        private static FarHost farHost(DhApiRenderParam param) {
            IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int colour;
            int depth;
            if (proxy.getRenderingEngine() == EDhApiRenderingEngine.BLAZE_3D) {
                colour = glId(proxy.getDhColorTextureBlazeWrapper());
                depth = glId(proxy.getDhDepthTextureBlazeWrapper());
            } else {
                colour = payload(proxy.getDhColorTextureGlId());
                depth = payload(proxy.getDhDepthTextureGlId());
            }
            Matrix4f viewToHostClip = DistantHorizonsFade.joml(param.dhProjectionMatrix)
                                                         .mul(DistantHorizonsFade.joml(param.dhModelViewMatrix))
                                                         .mul(DistantHorizonsFade.joml(param.mcModelViewMatrix)
                                                                                 .invert());
            return new FarHost(colour, depth, main.width, main.height, viewToHostClip,
                    proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE,
                    proxy.getDepthDirection().farDepth < 0.5f, -1);
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
                if (view != null) {
                    view.close();
                }
                view = glDepth = wrapForeign(id, main.width, main.height);
            }
            return view;
        }

        private static GlTextureView wrapForeign(int id, int width, int height) {
            // DH owns the texture ID; only the view is released when its size or ID changes.
            FrameBufferCache unused = new FrameBufferCache();
            GlTexture texture = new GlTexture(GpuTexture.USAGE_TEXTURE_BINDING, "Distant Horizons depth",
                    GpuFormat.D32_FLOAT, width, height, 1, 1, id, unused) {
            };
            return new GlTextureView(texture, 0, 1, unused) {
            };
        }

        private static int glId(DhApiResult<IDhApiBlazeTextureWrapper> wrapper) {
            return ((GlTexture) ((GpuTextureView) ((Object[]) payload(wrapper).getWrappedMcObject())[1]).texture())
                    .glId();
        }

        private static <T> T payload(DhApiResult<T> result) {
            if (!result.success) {
                throw new IllegalStateException(result.message);
            }
            return result.payload;
        }
    }
}
