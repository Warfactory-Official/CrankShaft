package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.impl.BackendManagerImpl;
import net.minecraft.client.Minecraft;
import net.optifine.shaders.IShaderPack;
import net.optifine.shaders.Program;
import net.optifine.shaders.Shaders;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

import java.util.IdentityHashMap;
import java.util.Map;

public final class OptifineIntegration {
    private static final Map<Program, String[]> SOURCES = new IdentityHashMap<>();
    public static boolean uploadingGuest;

    private OptifineIntegration() {
    }

    static void newGuestProgram() {
        // OptiFine caches locations and values by reusable GL program names, beyond a guest program's lifetime.
        var uniforms = Shaders.shaderUniforms;
        uniforms.reset();
        uniforms.setProgram(Shaders.activeProgramID);
        var custom = Shaders.customUniforms;
        if (custom != null) {
            custom.reset();
            custom.setProgram(Shaders.activeProgramID);
        }
    }

    public static IShaderPack patch(IShaderPack pack) {
        return OptifineShaderPack.wrap(pack);
    }

    public static DeferredReplayCompilation.Scope enterCompilation(Program program) {
        var nativePack = Shaders.getShaderPack();
        var plan = nativePack instanceof OptifineShaderPack patched ? patched.plan() : null;
        return DeferredReplayCompilation.enter(plan, program.getName());
    }

    public static void reloadStarted() {
        OptifineReplayBridge.close();
        OptifineHost.invalidate();
        SOURCES.clear();
        GuestBackends.rearm();
    }

    public static void reloadFinished() {
        var minecraft = Minecraft.getMinecraft();
        if (minecraft.world != null) BackendManagerImpl.onReloadLevelRenderer(minecraft.world);
    }

    public static int capturedPrograms() {
        return SOURCES.size();
    }

    public static void aliasesReloaded() {
        OptifineHost.aliasesReloaded();
    }

    public static void programsInvalidated() {
        if (Shaders.getShaderPack() instanceof OptifineShaderPack pack) pack.restage();
        reloadStarted();
    }

    // OptiFine detaches/deletes native shaders after linking; capture their complete sources while they still exist.
    public static void capture(Program program, int shader) {
        if (shader == 0) return;
        var index = switch (GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE)) {
            case GL20.GL_VERTEX_SHADER -> 0;
            case GL20.GL_FRAGMENT_SHADER -> 1;
            case GL32.GL_GEOMETRY_SHADER -> 2;
            default -> throw new UnsupportedOperationException("Unsupported OptiFine shader stage");
        };
        SOURCES.computeIfAbsent(program, _ -> new String[3])[index] = GL20.glGetShaderSource(shader);
    }

    static String[] sources(Program program) {
        for (var candidate = program; candidate != null; candidate = candidate.getProgramBackup()) {
            var source = SOURCES.get(candidate);
            if (source != null && source[0] != null && source[1] != null) return source;
            if (candidate.getProgramBackup() == candidate) break;
        }
        throw new IllegalStateException("OptiFine native shader sources were not captured for " + program.getName());
    }
}
