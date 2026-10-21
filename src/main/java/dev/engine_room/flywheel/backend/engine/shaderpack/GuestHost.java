package dev.engine_room.flywheel.backend.engine.shaderpack;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslPreprocessor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.util.*;

abstract class GuestHost {
    final GuestSamplerUnits samplers = new GuestSamplerUnits();
    final GuestSsbos storage = new GuestSsbos(this);
    final boolean suppressBlobShadows;
    private final Int2ObjectOpenHashMap<String> fragments = new Int2ObjectOpenHashMap<>();
    private final Map<String, GuestShaders.Demoted> demoted = new HashMap<>();
    private final Set<String> pureStages = new HashSet<>();
    private final Map<String, String> impureStages = new HashMap<>();
    private ContractProgramSet contracts;
    private Boolean tessellated;

    GuestHost(boolean suppressBlobShadows) {
        this.suppressBlobShadows = suppressBlobShadows;
    }

    static boolean active() {
        if (Loader.isModLoaded("ausm")) return AusmHost.active();
        return FMLClientHandler.instance().hasOptifine() && OptifineHost.active();
    }

    static GuestHost current() {
        if (Loader.isModLoaded("ausm")) return AusmHost.current();
        if (FMLClientHandler.instance().hasOptifine()) return OptifineHost.current();
        throw new IllegalStateException("No shader host is installed");
    }

    abstract boolean shadow();

    int shadowPlanes(float[] target) {
        return 0;
    }

    float shadowDistance() {
        return 0;
    }

    abstract String packName();

    abstract int dimension();

    abstract ContractPatches.Plan plan();

    abstract List<String> sourceCandidates(GuestShaderPack pack, String program) throws IOException;

    abstract String preprocess(GuestShaderPack pack, String path, ContractProgram program, int type) throws IOException;

    abstract Properties contractProperties(GuestShaderPack pack) throws IOException;

    abstract int blockId(IBlockState state);

    abstract int itemId(ItemStack item);

    abstract Map<ResourceLocation, Integer> entityIds();

    abstract Settings settings();

    abstract void begin(GuestRole role, GuestPhase phase);

    abstract void bind(GuestRole role, GuestPhase phase);

    abstract void end();

    abstract int program(GuestRole role);

    abstract int[] drawBuffers(GuestRole role);

    abstract void targets(GuestRole role, int[] buffers);

    abstract void materialBlend(GuestRole role, Material material, int[] buffers);

    void prepareMaterial() {
    }

    abstract GuestProgramHandle createProgram(GuestRole role);

    abstract void bindResources(GuestRole role, GuestProgramHandle program, GuestDraw draw, float alphaReference);

    abstract Iterable<Integer> storageBindings();

    abstract int storageHandle(int binding);

    abstract void restoreStorage();

    abstract boolean emissiveLight();

    boolean optifineLayout() {
        return false;
    }

    float tangentScale() {
        return 1;
    }

    final boolean deferredReplay() {
        return plan() != null && plan().replayProfile() != null;
    }

    TerrainBuffer.Slice terrainSlice(VertexBuffer buffer) {
        return TerrainBuffer.source(buffer);
    }

    BlockPos terrainOrigin(RenderChunk chunk, TerrainBuffer.Slice slice) {
        return TerrainBuffer.origin(chunk, slice);
    }

    final ContractProgramSet contracts() {
        if (contracts == null) contracts = ContractProgramSet.load(this);
        return contracts;
    }

    final GuestShaderPack openPack() throws IOException {
        return plan() == null ? new GuestShaderPack(packName()) : new GuestShaderPack(packName(), plan());
    }

    int[] shaders(GuestRole role, int program) {
        var result = new int[5];
        try (var stack = MemoryStack.stackPush()) {
            var shaders = stack.mallocInt(GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS));
            GL20.glGetAttachedShaders(program, null, shaders);
            for (var index = 0; index < shaders.limit(); index++) {
                var shader = shaders.get(index);
                switch (GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE)) {
                    case GL20.GL_VERTEX_SHADER -> result[0] = shader;
                    case GL20.GL_FRAGMENT_SHADER -> result[1] = shader;
                    case GL32.GL_GEOMETRY_SHADER -> result[2] = shader;
                    case GL40.GL_TESS_CONTROL_SHADER -> result[3] = shader;
                    case GL40.GL_TESS_EVALUATION_SHADER -> result[4] = shader;
                    default -> throw new IllegalStateException("Unexpected world shader stage");
                }
            }
        }
        return result;
    }

    final boolean tessellated() {
        if (tessellated != null) return tessellated;
        return tessellated = contracts().tessellated() || nativeTessellated();
    }

    abstract boolean nativeTessellated();

    final String fragment(GuestRenderPass pass) {
        var source = fragments.get(pass.fragmentShader);
        if (source == null) {
            source = GlslPreprocessor.preprocess(GL20.glGetShaderSource(pass.fragmentShader));
            fragments.put(pass.fragmentShader, source);
        }
        return source;
    }

    final GuestShaders.Demoted demote(String source) {
        return demoted.computeIfAbsent(source, GuestShaders::demote);
    }

    final void checkStageEffects(String source) {
        if (source == null || pureStages.contains(source)) return;
        var rejected = impureStages.get(source);
        if (rejected != null) throw new UnsupportedOperationException(rejected);
        try {
            GuestShaders.checkStageEffects(source);
            pureStages.add(source);
        } catch (UnsupportedOperationException error) {
            impureStages.put(source, error.getMessage());
            throw error;
        }
    }

    record Settings(boolean oldLighting, boolean separateAo, boolean shadowTranslucent, boolean shadowEntities,
                    boolean shadowPlayer, boolean shadowBlockEntities, boolean shadowLightBlockEntities) {
    }

}
