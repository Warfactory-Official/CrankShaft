package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.api.pipeline.fbo.Attachment;
import com.luna.ausm.api.pipeline.shader.RenderPass;
import com.luna.ausm.api.pipeline.shader.WorldRenderingPhase;
import com.luna.ausm.impl.pipeline.PipelineContext;
import com.luna.ausm.impl.pipeline.pack.*;
import com.luna.ausm.impl.pipeline.shader.ShaderProgram;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.*;

final class AusmHost extends GuestHost {
    private static final float[] NO_ENTITY_COLOR = new float[4];
    private static AusmHost current;
    private final ShaderProperties properties;
    private final int dimension;
    private final ContractPatches.Plan plan;
    private final ShaderProgram programEpoch;
    private final Map<BlendKey, ContractProperties.Blend[]> nativeBlends = new HashMap<>();

    private AusmHost(ShaderProperties properties, ShaderProgram epoch) {
        super(PipelineContext.getInstance().shouldDisableVanillaEntityShadows());
        this.properties = properties;
        programEpoch = epoch;
        dimension = ShaderDimensionContext.currentDimensionId();
        plan = AusmContractShaderProperties.of(properties).flywheel$patches();
    }

    static boolean active() {
        return PipelineContext.getInstance().isActive();
    }

    static AusmHost current() {
        var pipeline = PipelineContext.getInstance();
        var properties = pipeline.shaderProperties;
        var epoch = pipeline.effectivePipelineProgram(RenderPass.GBUFFERS_TERRAIN).shaderProgram();
        if (current == null || current.properties != properties || current.dimension != ShaderDimensionContext.currentDimensionId()
                || current.plan != AusmContractShaderProperties.of(properties)
                                                           .flywheel$patches() || current.programEpoch != epoch)
            current = new AusmHost(properties, epoch);
        return current;
    }

    private static RenderPass nativeRole(GuestRole role) {
        return RenderPass.valueOf(role.name());
    }

    private static WorldRenderingPhase nativePhase(GuestPhase phase) {
        return WorldRenderingPhase.valueOf(phase.name());
    }

    @Override
    boolean nativeTessellated() {
        for (var entry : PipelineContext.getInstance().programs.entrySet()) {
            var pass = entry.getKey().name();
            if (!pass.startsWith("GBUFFERS_") && !pass.startsWith("SHADOW")) continue;
            var shader = entry.getValue().shaderProgram();
            if (shader != null && shader.isTessellated()) return true;
        }
        return false;
    }

    @Override
    boolean shadow() {
        return PipelineContext.getInstance().isShadowPassActive();
    }

    @Override
    float shadowDistance() {
        // AUSM intentionally has an always-visible shadow camera; preserve its native hard distance bound.
        return (float) PipelineContext.getInstance().shadowRenderCullDistance();
    }

    @Override
    String packName() {
        return PipelineContext.getInstance().activePackName;
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
    int blockId(IBlockState state) {
        return properties.blockIds().idFor(state);
    }

    @Override
    int itemId(ItemStack item) {
        return properties.itemIds().idFor(item);
    }

    @Override
    Map<ResourceLocation, Integer> entityIds() {
        return properties.entityIds();
    }

    @Override
    Settings settings() {
        var settings = properties.renderSettings();
        return new Settings(settings.oldLighting(), settings.separateAo(), settings.shadowTranslucent(),
                settings.shadowEntities(),
                settings.shadowPlayer(), settings.shadowBlockEntities(), settings.shadowLightBlockEntities());
    }

    @Override
    void begin(GuestRole role, GuestPhase phase) {
        PipelineContext.getInstance().beginPass(nativeRole(role), nativePhase(phase));
    }

    @Override
    void bind(GuestRole role, GuestPhase phase) {
        var pipeline = PipelineContext.getInstance();
        pipeline.setPhase(nativePhase(phase));
        if (!pipeline.bindPass(nativeRole(role)))
            throw new IllegalStateException("AUSM world program is unavailable: " + role);
    }

    @Override
    void end() {
        PipelineContext.getInstance().endPass();
    }

    @Override
    int program(GuestRole role) {
        return PipelineContext.getInstance().effectivePipelineProgram(nativeRole(role)).shaderProgram().getId();
    }

    @Override
    int[] drawBuffers(GuestRole role) {
        var pipeline = PipelineContext.getInstance();
        return pipeline.programs.get(nativeRole(role)).effectiveDrawBuffers(pipeline.programs).stream()
                                .mapToInt(Attachment::getIndex).toArray();
    }

    @Override
    void targets(GuestRole role, int[] buffers) {
        var pipeline = PipelineContext.getInstance();
        var program = pipeline.effectivePipelineProgram(nativeRole(role));
        var attachments = Arrays.stream(buffers).mapToObj(Attachment::fromColorIndex).toList();
        if (role.shadow()) pipeline.configureShadowDrawBuffers(program, attachments);
        else pipeline.configureGbufferDrawBuffers(program, attachments);
    }

    @Override
    void materialBlend(GuestRole role, Material material, int[] buffers) {
        var modes = nativeBlends.computeIfAbsent(new BlendKey(role, material.transparency()), _ -> {
            var fallback = ContractProperties.Blend.material(material.transparency());
            for (var slot = 0; slot < buffers.length; slot++) fallback.apply(slot);
            PipelineContext.getInstance().applyBlendMode(nativeRole(role),
                    Arrays.stream(buffers).mapToObj(Attachment::fromColorIndex).toList());
            var captured = new ContractProperties.Blend[buffers.length];
            for (var slot = 0; slot < buffers.length; slot++) captured[slot] = ContractProperties.Blend.capture(slot);
            return captured;
        });
        for (var slot = 0; slot < modes.length; slot++) modes[slot].apply(slot);
    }

    @Override
    GuestProgramHandle createProgram(GuestRole role) {
        return new PipelineProgram();
    }

    @Override
    void bindResources(GuestRole role, GuestProgramHandle program, GuestDraw draw, float alphaReference) {
        var pipeline = PipelineContext.getInstance();
        var entity = pipeline.currentEntityId;
        var block = pipeline.dynamicBlockEntityId;
        var item = pipeline.currentRenderedItemId;
        var color = pipeline.currentEntityColor;
        var key = pipeline.currentEntityKey;
        var alpha = pipeline.currentAlphaTestReference;
        var tag = draw == null ? 0 : draw.tag(this);
        var itemTag = draw == null ? 0 : draw.itemTag(this);
        pipeline.currentEntityId = TaggedEnvironment.isEntity(tag) ? TaggedEnvironment.id(tag) : 0;
        pipeline.dynamicBlockEntityId = TaggedEnvironment.kind(
                tag) == TaggedEnvironment.KIND_BLOCK_ENTITY ? TaggedEnvironment.id(tag) : -1;
        if (TaggedEnvironment.kind(itemTag) == TaggedEnvironment.KIND_BLOCK_ITEM) pipeline.dynamicBlockEntityId = 1;
        pipeline.currentRenderedItemId = itemTag == 0 ? -1 : TaggedEnvironment.id(itemTag);
        pipeline.currentEntityColor = NO_ENTITY_COLOR;
        pipeline.currentEntityKey = draw == null ? null : draw.entityKey();
        if (!Float.isNaN(alphaReference)) pipeline.currentAlphaTestReference = alphaReference;
        try {
            pipeline.bindProgramResources(nativeRole(role), ((PipelineProgram) program).program);
        } finally {
            pipeline.currentEntityId = entity;
            pipeline.dynamicBlockEntityId = block;
            pipeline.currentRenderedItemId = item;
            pipeline.currentEntityColor = color;
            pipeline.currentEntityKey = key;
            pipeline.currentAlphaTestReference = alpha;
        }
    }

    @Override
    Iterable<Integer> storageBindings() {
        return PipelineContext.getInstance().shaderStorageBuffers.bindingIndices();
    }

    @Override
    int storageHandle(int binding) {
        return PipelineContext.getInstance().shaderStorageBuffers.glBufferId(binding);
    }

    @Override
    void restoreStorage() {
        PipelineContext.getInstance().shaderStorageBuffers.bind();
    }

    @Override
    List<String> sourceCandidates(GuestShaderPack pack, String program) throws IOException {
        var layout = ShaderPackLayout.detect(new PackSource(pack));
        var result = new LinkedHashSet<>(layout.dimensionSourceBaseAliases(dimension, program));
        result.add(layout.rootPath(program));
        return List.copyOf(result);
    }

    @Override
    String preprocess(GuestShaderPack pack, String path, ContractProgram program, int type) throws IOException {
        try (var scope = DeferredReplayCompilation.enter(plan, program.sourceName)) {
            return ShaderPreprocessor.processShaderSource(new PackSource(pack), path, properties.options(),
                    nativeRole(program.nativePass()),
                    type, program.sourceName, properties.packDirectives());
        }
    }

    @Override
    Properties contractProperties(GuestShaderPack pack) throws IOException {
        var source = new PackSource(pack);
        var layout = ShaderPackLayout.detect(source);
        var text = new StringBuilder();
        ShaderPropertiesPreprocessor.appendActiveProperties(source, layout, layout.rootPath("colorwheel.properties"),
                properties.options(), text, new HashSet<>());
        var result = new Properties();
        result.load(new StringReader(text.toString()));
        return result;
    }

    private record BlendKey(GuestRole role, Transparency transparency) {
    }

    private static final class PipelineProgram extends GuestProgramHandle {
        private final ShaderProgram program;

        private PipelineProgram() {
            this(new ShaderProgram("CrankShaft guest"));
        }

        private PipelineProgram(ShaderProgram program) {
            super(program.getId());
            this.program = program;
        }
    }

    private record PackSource(GuestShaderPack source) implements ShaderPack {
        @Override
        public String getName() {
            return source.getName();
        }

        @Override
        public InputStream getResourceAsStream(String path) throws IOException {
            return source.getResourceAsStream(path);
        }

        @Override
        public boolean hasResource(String path) {
            return source.hasResource(path);
        }

        @Override
        public void close() {
        }
    }
}
