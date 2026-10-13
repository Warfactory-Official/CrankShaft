package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Loading and configuration boundaries for native OIT compilation; no draw-time polling. */
public final class NativeOitWarmup {
    private static final List<Runnable> OWNER_PREPARATIONS = new ArrayList<>();
    private static final List<Runnable> DECLARATIONS = new ArrayList<>();
    private static volatile boolean initialized;
    private static volatile boolean ready;
    private static boolean customCloudsSupported;
    private static boolean preparingOwners;

    private NativeOitWarmup() {
    }

    /** Foreign renderer class initialization; hooks re-register current declarations after resource reload. */
    public static void registerDeclarations(Runnable declarations) {
        DECLARATIONS.add(declarations);
    }

    /** Client/module initialization; creates actual owners on the render thread before selected compilation. */
    public static void registerOwnerPreparation(Runnable preparation) {
        OWNER_PREPARATIONS.add(preparation);
    }

    /** Platform client initialization supplies this capability before resource-loading warmup. */
    public static void customCloudsSupported(boolean supported) {
        customCloudsSupported = supported;
    }

    static boolean supportsCustomClouds() {
        return customCloudsSupported;
    }

    public static boolean ready() {
        return ready;
    }

    public static void invalidate() {
        initialized = true;
        ready = false;
    }

    public static void loadingComplete() {
        ready = true;
        // A configuration reload or foreign declaration can arrive after the loading batch was collected.
        configurationChanged();
    }

    /** Joins the existing loading batch after native declarations are available. Render thread only. */
    public static void declareSelected() {
        if (!enabled()) return;
        preparingOwners = true;
        try {
            for (Runnable preparation : OWNER_PREPARATIONS) preparation.run();
        } finally {
            preparingOwners = false;
        }
        for (Runnable declarations : DECLARATIONS) declarations.run();
        if (VkContext.isVulkanHost()) NativeVkWarmup.precompileSelected(OitConfig.resolveInsertMode());
        else NativeOitPipelines.precompileSelected(BackendManager.currentBackend() == Backends.INSTANCING
                    ? null : OitConfig.resolveInsertMode());
    }

    /** Keeps off-thread configuration publication and its compilation together before rendering resumes. */
    public static void configure(Runnable changes) {
        if (initialized && !RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().schedule(() -> configure(changes));
            return;
        }
        changes.run();
        configurationChanged();
    }

    public static void configurationChanged() {
        if (!ready) return;
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().schedule(NativeOitWarmup::configurationChanged);
            return;
        }
        // A constructor's nested request is covered by the enclosing warmup immediately after owner preparation.
        if (preparingOwners) return;
        if (enabled()) RenderPipelineCompiler.warm(NativeOitWarmup::declareSelected);
    }

    private static boolean enabled() {
        if (!BackendManager.isBackendOn()
                || ShadersModHelper.isShaderPackInUse()
                || !Minecraft.getInstance().options.improvedTransparency().get()) return false;
        return supports(BackendManager.currentBackend());
    }

    /** Native Improved Transparency producer backend on the current host. */
    public static boolean supports(Backend backend) {
        return VkContext.isVulkanHost()
                ? backend == Backends.VK_INDIRECT
                || Backends.VK_MESH_SHADER_ID.equals(Backend.REGISTRY.getIdOrThrow(backend))
                : backend == Backends.INSTANCING || backend == Backends.INDIRECT
                        || Backends.GL_MESH_SHADER_ID.equals(Backend.REGISTRY.getIdOrThrow(backend));
    }
}
