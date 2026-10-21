package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.optifine.shaders.*;
import net.optifine.shaders.config.MacroProcessor;
import net.optifine.shaders.config.ShaderPackParser;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class OptifineHost extends GuestHost {
    private static final MethodHandle UNLOCK_ALPHA = unlock("unlockAlpha");
    private static final MethodHandle UNLOCK_BLEND = unlock("unlockBlend");
    private static OptifineHost current;
    private final IShaderPack pack;
    private final int dimension;
    private final ContractPatches.Plan plan;
    private final Map<ResourceLocation, Integer> entities = new HashMap<>();
    private final ArrayDeque<NativeState> states = new ArrayDeque<>();
    private final Map<Program, int[]> compiledSources = new HashMap<>();
    private final Map<GuestRole, ContractProperties.Blend[]> blends = new HashMap<>();

    private OptifineHost(IShaderPack pack, int dimension) {
        super(Shaders.shouldSkipDefaultShadow);
        this.pack = pack;
        this.dimension = dimension;
        plan = pack instanceof OptifineShaderPack patched ? patched.plan() : null;
        for (var key : EntityList.getEntityNameList()) {
            var type = EntityList.getClass(key);
            var id = EntityAliases.getEntityAliasId(EntityList.getID(type));
            if (id >= 0) entities.put(key, id);
        }
    }

    static boolean active() {
        return Shaders.shaderPackLoaded && !"(internal)".equals(Shaders.getShaderPackName());
    }

    static OptifineHost current() {
        var pack = Shaders.getShaderPack();
        var world = Minecraft.getMinecraft().world;
        var dimension = world == null ? 0 : world.provider.getDimensionType().getId();
        if (current == null || current.pack != pack || current.dimension != dimension)
            current = new OptifineHost(pack, dimension);
        return current;
    }

    static void invalidate() {
        if (current != null) for (var stages : current.compiledSources.values())
            for (var stage : stages)
                if (stage != 0) GL20.glDeleteShader(stage);
        current = null;
    }

    static void aliasesReloaded() {
        invalidate();
    }

    private static void uniform(int program, String name, int value) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), value);
    }

    private static MethodHandle unlock(String name) {
        try {
            return MethodHandles.publicLookup()
                                .findStatic(GlStateManager.class, name, MethodType.methodType(void.class));
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void releaseLocks() {
        try {
            UNLOCK_ALPHA.invokeExact();
            UNLOCK_BLEND.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("OptiFine state locks could not be released", error);
        }
    }

    @Override
    boolean shadow() {
        return Shaders.isShadowPass;
    }

    @Override
    int shadowPlanes(float[] target) {
        // OptiFine's caster frustum includes the light extrusion of off-camera receivers.
        var frustum = (ClippingHelperShadow) ClippingHelperShadow.getInstance();
        var count = frustum.shadowClipPlaneCount;
        if (count < 0 || count > GuestShadowCull.MAX_PLANES)
            throw new IllegalStateException("Unsupported shadow plane count " + count);
        var planes = frustum.shadowClipPlanes;
        for (var index = 0; index < count; index++) System.arraycopy(planes[index], 0, target, index * 4, 4);
        return count;
    }

    @Override
    boolean optifineLayout() {
        return true;
    }

    @Override
    float tangentScale() {
        return 32767;
    }

    @Override
    String packName() {
        return Shaders.getShaderPackName();
    }

    @Override
    int dimension() {
        return dimension;
    }

    @Override
    ContractPatches.Plan plan() {
        return plan;
    }

    @Override
    boolean emissiveLight() {
        return plan != null && plan.emissiveLight();
    }

    @Override
    Map<ResourceLocation, Integer> entityIds() {
        return entities;
    }

    @Override
    int blockId(IBlockState state) {
        var block = Block.getIdFromBlock(state.getBlock());
        var alias = BlockAliases.getBlockAliasId(block, state.getBlock().getMetaFromState(state));
        return alias < 0 ? block : alias;
    }

    @Override
    int itemId(ItemStack item) {
        var id = Item.getIdFromItem(item.getItem());
        var alias = ItemAliases.getItemAliasId(id);
        return alias < 0 ? id : alias;
    }

    @Override
    Settings settings() {
        return new Settings(Shaders.isOldLighting(), Shaders.isSeparateAo(), Shaders.isRenderShadowTranslucent(), true,
                true, true, false);
    }

    private Program nativeProgram(GuestRole role) {
        if (role.shadow()) return Shaders.ProgramShadow;
        return switch (role) {
            case GBUFFERS_TERRAIN_SOLID -> Shaders.ProgramTerrainSolid;
            case GBUFFERS_TERRAIN_CUTOUT_MIP -> Shaders.ProgramTerrainCutoutMip;
            case GBUFFERS_TERRAIN_CUTOUT -> Shaders.ProgramTerrainCutout;
            case GBUFFERS_BLOCK, GBUFFERS_BLOCK_TRANSLUCENT -> Shaders.ProgramBlock;
            case GBUFFERS_ENTITIES, GBUFFERS_ENTITIES_TRANSLUCENT, GBUFFERS_LIGHTNING -> Shaders.ProgramEntities;
            case GBUFFERS_WATER -> Shaders.ProgramWater;
            case GBUFFERS_DAMAGEDBLOCK -> Shaders.ProgramDamagedBlock;
            case GBUFFERS_BEACONBEAM -> Shaders.ProgramBeaconBeam;
            case GBUFFERS_ARMOR_GLINT -> Shaders.ProgramArmorGlint;
            case GBUFFERS_SPIDEREYES -> Shaders.ProgramSpiderEyes;
            default -> Shaders.ProgramTerrain;
        };
    }

    @Override
    void begin(GuestRole role, GuestPhase phase) {
        states.push(new NativeState(Shaders.activeProgram, Shaders.activeDrawBuffers,
                GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)));
        bind(role, phase);
    }

    @Override
    void bind(GuestRole role, GuestPhase phase) {
        Shaders.useProgram(nativeProgram(role));
        var modes = new ContractProperties.Blend[drawBuffers(role).length];
        for (var slot = 0; slot < modes.length; slot++) modes[slot] = ContractProperties.Blend.capture(slot);
        blends.put(role, modes);
        var alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        var function = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        var reference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        // OptiFine locks pack overrides in GlStateManager; guest OIT must control its private attachments.
        releaseLocks();
        GlStateManager.alphaFunc(function, reference);
        if (alpha) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
        for (var slot = 0; slot < modes.length; slot++) modes[slot].apply(slot);
    }

    @Override
    void end() {
        var state = states.pop();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.framebuffer);
        Shaders.useProgram(state.program);
        Shaders.setDrawBuffers(state.drawBuffers);
    }

    @Override
    int program(GuestRole role) {
        return nativeProgram(role).getId();
    }

    @Override
    int[] drawBuffers(GuestRole role) {
        var buffers = nativeProgram(role).getDrawBuffers();
        if (buffers == null) return new int[]{0};
        var result = new int[buffers.remaining()];
        for (var index = 0; index < result.length; index++)
            result[index] = buffers.get(buffers.position() + index) - GL30.GL_COLOR_ATTACHMENT0;
        return result;
    }

    @Override
    void targets(GuestRole role, int[] buffers) {
        var drawBuffers = BufferUtils.createIntBuffer(buffers.length);
        for (var buffer : buffers) drawBuffers.put(buffer < 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0 + buffer);
        drawBuffers.flip();
        Shaders.setDrawBuffers(drawBuffers);
    }

    @Override
    void materialBlend(GuestRole role, Material material, int[] buffers) {
        var fallback = role.shadow() ? ContractProperties.Blend.OFF : ContractProperties.Blend.material(
                material.transparency());
        var overrides = nativeProgram(role).getBlendState() != null ? blends.get(role) : null;
        for (var slot = 0; slot < buffers.length; slot++)
            (overrides == null ? fallback : overrides[Math.min(slot, overrides.length - 1)]).apply(slot);
    }

    @Override
    GuestProgramHandle createProgram(GuestRole role) {
        return new OptifineProgram(nativeProgram(role));
    }

    // ShadersTex updates atlasSize during material binds, including after guest compute dispatches.
    @Override
    void prepareMaterial() {
        GlProgram.bind(Shaders.activeProgramID);
    }

    @Override
    void bindResources(GuestRole role, GuestProgramHandle program, GuestDraw draw, float alphaReference) {
        var shadow = Shaders.isShadowPass;
        var renderingDfb = Shaders.isRenderingDfb;
        OptifineIntegration.uploadingGuest = true;
        try {
            // useProgram owns built-in/custom uniform uploads; its native FBO and blend changes stay outside this scope.
            Shaders.isShadowPass = false;
            Shaders.isRenderingDfb = false;
            Shaders.useProgram(((OptifineProgram) program).program);
            var tag = draw == null ? 0 : draw.tag(this);
            var itemTag = draw == null ? 0 : draw.itemTag(this);
            uniform(program.id, "entityId", TaggedEnvironment.isEntity(tag) ? TaggedEnvironment.id(tag) : 0);
            uniform(program.id, "blockEntityId",
                    TaggedEnvironment.kind(tag) == TaggedEnvironment.KIND_BLOCK_ENTITY ? TaggedEnvironment.id(
                            tag) : -1);
            uniform(program.id, "currentRenderedItemId", itemTag == 0 ? -1 : TaggedEnvironment.id(itemTag));
            GL20.glUniform4f(GL20.glGetUniformLocation(program.id, "entityColor"), 0, 0, 0, 0);
            if (!Float.isNaN(alphaReference))
                GL20.glUniform1f(GL20.glGetUniformLocation(program.id, "alphaTestRef"), alphaReference);
        } finally {
            Shaders.isShadowPass = shadow;
            Shaders.isRenderingDfb = renderingDfb;
            OptifineIntegration.uploadingGuest = false;
        }
    }

    @Override
    int[] shaders(GuestRole role, int program) {
        return compiledSources.computeIfAbsent(nativeProgram(role), source -> {
            var originals = OptifineIntegration.sources(source);
            var stages = new int[5];
            try {
                stages[0] = GuestPrograms.compile(GL20.GL_VERTEX_SHADER, originals[0]);
                stages[1] = GuestPrograms.compile(GL20.GL_FRAGMENT_SHADER, originals[1]);
                if (originals[2] != null) stages[2] = GuestPrograms.compile(GL32.GL_GEOMETRY_SHADER, originals[2]);
                return stages;
            } catch (RuntimeException error) {
                for (var shader : stages) if (shader != 0) GL20.glDeleteShader(shader);
                throw error;
            }
        });
    }

    // G6 exposes native vertex/fragment/geometry sources only; avoid compiling unused native roles to probe for TES.
    @Override
    boolean nativeTessellated() {
        return false;
    }

    @Override
    Iterable<Integer> storageBindings() {
        return OptifineReplayBridge.storageBindings();
    }

    @Override
    int storageHandle(int binding) {
        return OptifineReplayBridge.storageHandle(binding);
    }

    @Override
    void restoreStorage() {
        OptifineReplayBridge.restoreStorage();
    }

    @Override
    List<String> sourceCandidates(GuestShaderPack pack, String program) {
        return List.of(pack.prefix() + "world" + dimension + "/" + program, pack.prefix() + program);
    }

    @Override
    String preprocess(GuestShaderPack pack, String path, ContractProgram program, int type) throws IOException {
        try (var stream = pack.getResourceAsStream(path)) {
            var reader = new BufferedReader(
                    new StringReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            try (var resolved = ShaderPackParser.resolveIncludes(reader, "/" + path, new PackSource(pack), 0,
                    new ArrayList<>(), 0)) {
                var source = new StringBuilder();
                var options = Shaders.getShaderPackOptions();
                for (var line = resolved.readLine(); line != null; line = resolved.readLine())
                    source.append(Shaders.applyOptions(line, options)).append('\n');
                return DeferredReplayCompilation.transform(plan, program.sourceName, source.toString(), type);
            }
        }
    }

    @Override
    Properties contractProperties(GuestShaderPack pack) throws IOException {
        var result = new Properties();
        var source = pack.read("colorwheel.properties");
        if (source == null) return result;
        var defines = new StringBuilder();
        for (var option : Shaders.getShaderPackOptions()) {
            var line = option.getSourceLine();
            if (line != null && line.startsWith("#")) defines.append(line).append('\n');
        }
        try (var input = new ByteArrayInputStream((defines + source).getBytes(StandardCharsets.UTF_8));
             var processed = MacroProcessor.process(input, "colorwheel.properties")) {
            result.load(processed);
        }
        return result;
    }

    private record NativeState(Program program, IntBuffer drawBuffers, int framebuffer) {
    }

    private static final class OptifineProgram extends GuestProgramHandle {
        private final Program program;

        private OptifineProgram(Program nativeProgram) {
            super(GL20.glCreateProgram());
            OptifineIntegration.newGuestProgram();
            program = new Program(0, "CrankShaft guest", nativeProgram.getProgramStage(), nativeProgram);
            program.copyFrom(nativeProgram);
            program.setId(id);
        }
    }

    private record PackSource(GuestShaderPack source) implements IShaderPack {
        @Override
        public String getName() {
            return source.getName();
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            try {
                return source.getResourceAsStream(path.startsWith("/") ? path.substring(1) : path);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override
        public boolean hasDirectory(String path) {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
