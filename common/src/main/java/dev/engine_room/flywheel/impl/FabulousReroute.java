package dev.engine_room.flywheel.impl;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.engine.FabulousCaptures;
import dev.engine_room.flywheel.backend.engine.FabulousLayerTargets;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

/**
 * Improved Transparency reroute: clouds + weather are captured at the OIT seam and suppressed once composited.
 */
public final class FabulousReroute {
    private static final FabulousCaptures CAPTURES = new FabulousCaptures();
    private static boolean capturing;
    private static boolean suppressClouds;
    private static boolean suppressWeather;
    @Nullable
    private static Object levelSubmits;

    private FabulousReroute() {
    }

    public static void levelSubmits(Object storage) {
        levelSubmits = storage;
    }

    public static boolean isLevelFrame(@Nullable Object frameStorage) {
        return frameStorage != null && frameStorage == levelSubmits;
    }

    public static void beginLayerWindow() {
        if (active()) {
            FabulousLayerTargets.prepare();
        } else {
            FabulousLayerTargets.delete();
        }
    }

    public static void closeLayerWindow() {
        FabulousLayerTargets.closeWindow();
    }

    public static boolean active() {
        return BackendManagerImpl.isBackendOn()
                && BackendConfig.INSTANCE.terrainMode().compositesTranslucent()
                && Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()
                && !BackendDebugFlags.SKIP_OIT
                && NoiseTextures.BLUE_NOISE != null;
    }

    @Nullable
    public static FabulousCaptures capture(RenderContext context) {
        suppressClouds = false;
        suppressWeather = false;
        CAPTURES.clear();
        if (!active()) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        GameRenderState gameState = mc.gameRenderer.gameRenderState();
        LevelRenderState levelState = gameState.levelRenderState;
        OptionsRenderState optionsState = gameState.optionsRenderState;

        CloudStatus cloudStatus = optionsState.cloudStatus;
        if (FlwImplXplat.INSTANCE.vanillaOwnsClouds() && cloudStatus != CloudStatus.OFF
                && ARGB.alpha(levelState.cloudColor) > 0) {
            capturing = true;
            try {
                mc.levelRenderer.cloudRenderer()
                                .render(levelState.cloudColor, cloudStatus, levelState.cloudHeight,
                                        optionsState.cloudRange,
                                        levelState.cameraRenderState.pos, levelState.gameTime, context.partialTick());
            } finally {
                capturing = false;
            }
        }

        if (FlwImplXplat.INSTANCE.vanillaOwnsWeather()) {
            capturing = true;
            try {
                mc.levelRenderer.weatherEffectRenderer()
                                .render(levelState.cameraRenderState.pos, levelState.weatherRenderState);
            } finally {
                capturing = false;
            }
        }

        CAPTURES.itemLayerColor = FabulousLayerTargets.itemColorView();
        CAPTURES.itemLayerDepth = FabulousLayerTargets.itemDepthView();
        CAPTURES.particleLayerColor = FabulousLayerTargets.particleColorView();
        CAPTURES.particleLayerDepth = FabulousLayerTargets.particleDepthView();

        if (!CAPTURES.hasAny()) {
            return null;
        }

        int maxQuads = Math.max(CAPTURES.cloudQuads, CAPTURES.rainColumns + CAPTURES.snowColumns);
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
                    .getBuffer(6 * maxQuads);
        return CAPTURES;
    }

    public static void onComposited(boolean composited) {
        suppressClouds = composited && CAPTURES.hasClouds();
        suppressWeather = composited && CAPTURES.hasWeather();
    }

    public static boolean consumeSuppressClouds() {
        if (capturing) {
            return false;
        }
        boolean suppress = suppressClouds;
        suppressClouds = false;
        return suppress;
    }

    public static boolean consumeSuppressWeather() {
        if (capturing) {
            return false;
        }
        boolean suppress = suppressWeather;
        suppressWeather = false;
        return suppress;
    }

    /**
     * Pre-draw seam of {@code CloudRenderer.render}.
     */
    public static boolean captureClouds(GpuBuffer cloudInfo, GpuBuffer cloudFaces, int quadCount, boolean fancy) {
        if (!capturing) {
            return false;
        }
        CAPTURES.cloudInfo = cloudInfo;
        CAPTURES.cloudFaces = cloudFaces;
        CAPTURES.cloudQuads = quadCount;
        CAPTURES.cloudsFancy = fancy;
        CAPTURES.cloudsTransform = RenderSystem.getDynamicUniforms()
                                               .writeTransform(RenderSystem.getModelViewMatrixCopy());
        return true;
    }

    public static boolean captureWeather(GpuBuffer vertices, int rainColumns, int snowColumns) {
        if (!capturing) {
            return false;
        }
        CAPTURES.weatherVertices = vertices;
        CAPTURES.rainColumns = rainColumns;
        CAPTURES.snowColumns = snowColumns;
        CAPTURES.weatherTransform = RenderSystem.getDynamicUniforms()
                                                .writeTransform(RenderSystem.getModelViewMatrixCopy());
        return true;
    }
}
