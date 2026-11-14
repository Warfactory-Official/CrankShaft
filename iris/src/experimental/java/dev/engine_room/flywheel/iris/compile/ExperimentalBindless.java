package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import net.irisshaders.iris.samplers.VulkanHeapLayout;
import net.irisshaders.iris.vulkan.VulkanPackResources;
import net.irisshaders.iris.vulkan.VulkanSamplerHeap;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Merged-texture experimental guest batches: albedo from the GL handle table or flywheel's Vulkan descriptor table
 * (set {@link #SET}), indexed by the draw command's slot.
 */
public final class ExperimentalBindless {
    /**
     * After Iris's uniform, heap, frame and draw sets.
     */
    public static final int SET = 4;
    private static final int DRAW_SET = 3;
    private static final Set<String> ALBEDO = Set.of("tex", "texture", "gtexture", "u_MainSampler", "Sampler0");
    private static final Map<String, String> ALBEDO_UNIFORMS = Map.of(
            "atlasSize", "_flw_guestTextureInfo[_flw_guestVertexTexIndex * 2u].xy",
            "gtextureSize", "_flw_guestTextureInfo[_flw_guestVertexTexIndex * 2u].zw",
            "gtextureId", "_flw_guestTextureInfo[_flw_guestVertexTexIndex * 2u + 1u].x");
    private static final String HANDLES = "layout(std430) restrict readonly buffer _FlwTextureHandles "
            + "{ uvec2 _flw_textureHandles[]; };";
    private static final String INFO = "layout(std430) restrict readonly buffer _FlwGuestTextureInfo "
            + "{ ivec4 _flw_guestTextureInfo[]; };";
    private static final String VERTEX_SLOT = "_flw_guestVertexTexIndex";
    private static final Set<Long> TABLE_LAYOUTS = ConcurrentHashMap.newKeySet();

    private ExperimentalBindless() {
    }

    public static boolean available() {
        if (!VkContext.isVulkanHost()) return GlCompat.SUPPORTS_BINDLESS_TEXTURES;
        if (!VkCaps.BINDLESS_TEXTURES_NEGOTIATED || VkCaps.MAX_BOUND_DESCRIPTOR_SETS <= SET) return false;
        return heap().flywheel$supportsTextureTable();
    }

    private static VulkanSamplerHeap heap() {
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        return ((VulkanPackResources) pipeline.getPackSamplers().getResources()).getSamplerHeap();
    }

    /**
     * Iris's fixed heap with the table, before any program's draw descriptors.
     */
    public static boolean fits(Limits limits) {
        return fitsDescriptors(limits, 0, 0, 0, 0);
    }

    /**
     * Draw samplers count with heap + table; texel buffers count as sampled images.
     */
    private static boolean fitsDescriptors(Limits limits, long stageSamplers, long stageImages, long setSamplers,
                                           long setImages) {
        long table = VkCaps.BINDLESS_TABLE_CAPACITY;
        long samplers = VulkanHeapLayout.SAMPLER_COUNT + table;
        long images = VulkanHeapLayout.IMAGE_COUNT + VulkanHeapLayout.TEXEL_COUNT + table;
        return samplers + stageSamplers <= limits.stageSamplers() && samplers + setSamplers <= limits.setSamplers()
                && images + stageImages <= limits.stageImages() && images + setImages <= limits.setImages();
    }

    /**
     * Final resource-set stages, including the shared frame layout and the fragment attachments.
     */
    public static boolean fitsResources(ShaderResourceLayout program, @Nullable ShaderResourceLayout frame,
                                        int colorAttachments, Limits limits) {
        long shared = VulkanHeapLayout.SAMPLER_COUNT + VulkanHeapLayout.IMAGE_COUNT + VulkanHeapLayout.TEXEL_COUNT
                + VkCaps.BINDLESS_TABLE_CAPACITY;
        long setSamplers = 0, setImages = 0;
        for (var descriptor : program.descriptors()) {
            if (descriptor.set() != DRAW_SET || descriptor.kind() != ShaderResourceLayout.Kind.SAMPLER) continue;
            if (!SamplerHeapLayout.Type.valueOf(descriptor.type()).texelBuffer()) setSamplers += descriptor.count();
            setImages += descriptor.count();
        }
        for (var stage : List.of(PatchShaderType.VERTEX, PatchShaderType.FRAGMENT)) {
            long stageSamplers = 0, stageImages = 0;
            for (var descriptor : program.descriptors()) {
                if (descriptor.set() != DRAW_SET || descriptor.kind() != ShaderResourceLayout.Kind.SAMPLER
                        || !descriptor.stages().contains(stage)) continue;
                if (!SamplerHeapLayout.Type.valueOf(descriptor.type()).texelBuffer())
                    stageSamplers += descriptor.count();
                stageImages += descriptor.count();
            }
            long resources = shared + stageResources(program, 0, stage) + stageResources(program, DRAW_SET, stage)
                    + stageResources(frame, 2, stage) + (stage == PatchShaderType.FRAGMENT ? colorAttachments : 0);
            if (resources > limits.stageResources()
                    || !fitsDescriptors(limits, stageSamplers, stageImages, setSamplers, setImages)) return false;
        }
        return true;
    }

    private static long stageResources(@Nullable ShaderResourceLayout layout, int set, PatchShaderType stage) {
        if (layout == null) return 0;
        long count = 0;
        for (var descriptor : layout.descriptors()) {
            if (descriptor.set() == set && descriptor.stages().contains(stage)) count += descriptor.count();
        }
        return count;
    }

    static boolean admits(PatchedShader shader, @Nullable ShaderResourceLayout frame, int colorAttachments) {
        // Set zero carries the table metadata.
        if (shader.layout().descriptors().stream().noneMatch(descriptor -> descriptor.set() == 0)) return false;
        return fitsResources(shader.layout(), frame, colorAttachments, heap().flywheel$textureTableLimits());
    }

    /**
     * Resource-set creation/retirement can run on Iris's compilation workers.
     */
    public static void registerLayout(long handle, ShaderResourceLayout resources, int set) {
        if (set != 0) return;
        for (var descriptor : resources.descriptors()) {
            if (descriptor.set() == SET && descriptor.name().equals("_flw_textures")) {
                TABLE_LAYOUTS.add(handle);
                return;
            }
        }
    }

    public static void retireLayout(long handle) {
        TABLE_LAYOUTS.remove(handle);
    }

    public static boolean usesTable(long handle) {
        return TABLE_LAYOUTS.contains(handle);
    }

    /**
     * Before the pipeline's creation: layouts made earlier lack the table.
     */
    static void prepareTable() {
        if (VkContext.isVulkanHost()) VkBindlessTable.setLayoutHandle();
    }

    /**
     * Pack albedo samplers -> table reads, Iris's albedo-derived uniforms -> flat varyings fed from
     * {@link GuestTextureInfo}. Fragment slot: Vulkan descriptor index, GL handle ({@code sampler2D} varyings fail
     * Iris's layout transform). {@code null}: Iris's albedo binding stays (extra stages relay no varyings; an albedo
     * name not a plain global {@code sampler2D}; Vulkan: albedo passed to a pack function, whose sampler parameters
     * Iris lowers to heap indices).
     */
    static @Nullable Map<PatchShaderType, String> convert(Map<PatchShaderType, String> sources) {
        if (sources.size() != 2) return null;
        boolean vulkan = VkContext.isVulkanHost();
        Stage fragment = stage(sources.get(PatchShaderType.FRAGMENT), null, vulkan);
        if (fragment == null) return null;
        Stage vertex = stage(sources.get(PatchShaderType.VERTEX), fragment, vulkan);
        if (vertex == null) return null;
        Map<PatchShaderType, String> converted = new EnumMap<>(PatchShaderType.class);
        converted.put(PatchShaderType.VERTEX, vertex.source());
        converted.put(PatchShaderType.FRAGMENT, fragment.source());
        return converted;
    }

    /**
     * {@code fragment}: converted fragment stage, which the vertex stage feeds; {@code null} converts the fragment.
     */
    private static @Nullable Stage stage(String source, @Nullable Stage fragment, boolean vulkan) {
        boolean vertex = fragment != null;
        String table = vulkan ? "_flw_textures[nonuniformEXT(" + (vertex ? VERTEX_SLOT : "_flw_guestTexIndex") + ")]"
                : vertex ? "sampler2D(_flw_textureHandles[" + VERTEX_SLOT + "])" : "sampler2D(_flw_guestAlbedoHandle)";
        Map<String, String> fragmentVaryings = vertex ? fragment.varyings() : Map.of();
        boolean[] result = new boolean[2];
        Map<String, String> varyings = new LinkedHashMap<>();
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            List<DeclarationMember> albedo = new ArrayList<>();
            for (ExternalDeclaration external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)
                        || !uniform(typed.getType().getTypeQualifier())) {
                    continue;
                }
                String type = ASTPrinter.printSimple(typed.getType().getTypeSpecifier()).strip();
                for (DeclarationMember member : List.copyOf(typed.getMembers())) {
                    String name = member.getName().getName();
                    boolean sampler = ALBEDO.contains(name);
                    if (!sampler && !ALBEDO_UNIFORMS.containsKey(name)) continue;
                    if (member.getArraySpecifier() != null || sampler && !type.equals("sampler2D")
                            || !type.equals(fragmentVaryings.getOrDefault(name, type))) {
                        result[1] = true;
                        return;
                    }
                    if (sampler) {
                        albedo.add(member);
                        continue;
                    }
                    varyings.put(name, type);
                    ExternalDeclaration varying = parser.parseExternalDeclaration(root,
                            (vertex ? "flat out " : "flat in ") + type + " " + name + ";");
                    if (typed.getMembers().size() == 1) {
                        external.replaceByAndDelete(varying);
                    } else {
                        member.detachAndDelete();
                        tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, varying);
                    }
                }
            }
            Set<String> functions = vulkan ? root.nodeIndex.getStream(FunctionPrototype.class)
                                                           .map(prototype -> prototype.getName().getName())
                                                           .collect(Collectors.toSet()) : Set.of();
            List<ReferenceExpression> references = new ArrayList<>();
            for (DeclarationMember member : albedo) {
                for (Identifier identifier : root.identifierIndex.getStream(member.getName().getName()).toList()) {
                    if (identifier.getParent() instanceof ReferenceExpression reference) {
                        if (reference.getParent() instanceof FunctionCallExpression call
                                && call.getFunctionName() != null
                                && functions.contains(call.getFunctionName().getName())) {
                            result[1] = true;
                            return;
                        }
                        references.add(reference);
                    } else if (identifier.getParent() != member
                            && !(identifier.getParent() instanceof FunctionCallExpression)) {
                        result[1] = true;
                        return;
                    }
                }
            }
            for (DeclarationMember member : albedo) {
                var typed = (TypeAndInitDeclaration) member.getParent();
                if (typed.getMembers().size() == 1) typed.getParent().detachAndDelete();
                else member.detachAndDelete();
            }
            for (ReferenceExpression reference : references) {
                reference.replaceByAndDelete(parser.parseExpression(root, table));
            }
            result[0] = !references.isEmpty();
            String slot = vulkan ? "uint _flw_guestTexIndex" : "uvec2 _flw_guestAlbedoHandle";
            List<String> declarations = new ArrayList<>();
            if (vertex) {
                for (var entry : fragmentVaryings.entrySet()) {
                    if (varyings.containsKey(entry.getKey())) continue;
                    if (root.identifierIndex.has(entry.getKey())) {
                        result[1] = true;
                        return;
                    }
                    declarations.add("flat out " + entry.getValue() + " " + entry.getKey() + ";");
                    varyings.put(entry.getKey(), entry.getValue());
                }
                if (!vulkan && (result[0] || fragment.sampled())) declarations.add(HANDLES);
                if (!varyings.isEmpty()) declarations.add(INFO);
                if (fragment.sampled()) declarations.add("flat out " + slot + ";");
                declarations.add("uint " + VERTEX_SLOT + ";");
                List<String> assignments = new ArrayList<>();
                assignments.add(VERTEX_SLOT + " = _flw_drawCommands[_flw_baseDraw + uint(gl_DrawIDARB)]"
                        + ".packedTexIndices & 0xFFFFu;");
                if (fragment.sampled()) assignments.add(vulkan ? "_flw_guestTexIndex = " + VERTEX_SLOT + ";"
                        : "_flw_guestAlbedoHandle = _flw_textureHandles[" + VERTEX_SLOT + "];");
                varyings.forEach((name, type) -> assignments.add(name + " = " + type + "("
                        + ALBEDO_UNIFORMS.get(name) + ");"));
                assign(parser, root, tree, assignments);
            } else if (result[0]) {
                declarations.add("flat in " + slot + ";");
            }
            if (!vulkan && result[0]) {
                GuestShaders.preamble(parser, tree,
                        compilation -> compilation.requireExtension("GL_ARB_bindless_texture"));
            }
            for (String declaration : declarations) {
                tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS,
                        parser.parseExternalDeclaration(root, declaration));
            }
        });
        String transformed = parser.transform(source);
        if (result[1]) return null;
        return new Stage(transformed, result[0], varyings);
    }

    private static void assign(SingleASTTransformer<JobParameters> parser, Root root, TranslationUnit tree,
                               List<String> assignments) {
        for (ExternalDeclaration external : tree.getChildren()) {
            if (!(external instanceof FunctionDefinition main)
                    || !main.getFunctionPrototype().getName().getName().equals("main")) {
                continue;
            }
            var statements = main.getBody().getStatements();
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i) instanceof ExpressionStatement statement
                        && statement.getExpression() instanceof FunctionCallExpression call
                        && call.getFunctionName() != null
                        && call.getFunctionName().getName().equals("_flw_guestVertex")) {
                    for (int a = 0; a < assignments.size(); a++) {
                        statements.add(i + 1 + a, parser.parseStatement(root, assignments.get(a)));
                    }
                    return;
                }
            }
        }
        throw new IllegalStateException("Guest vertex main without _flw_guestVertex()");
    }

    private static boolean uniform(@Nullable TypeQualifier qualifier) {
        return qualifier != null && qualifier.getParts().stream().anyMatch(part -> part instanceof StorageQualifier
                storage && storage.storageType == StorageQualifier.StorageType.UNIFORM);
    }

    /**
     * Vulkan, after Iris's patch.
     */
    static PatchedShader declareTable(PatchedShader shader) {
        String declaration = "layout(set = " + SET + ", binding = 0) uniform sampler2D _flw_textures["
                + VkCaps.BINDLESS_TABLE_CAPACITY + "];\n";
        Map<PatchShaderType, String> sources = new EnumMap<>(PatchShaderType.class);
        shader.sources().forEach((stage, text) -> {
            if (!text.contains("_flw_textures[")) {
                sources.put(stage, text);
                return;
            }
            var parser = GuestShaders.versionedTransformer();
            parser.setTransformation((tree, _) -> {
                GuestShaders.preamble(parser, tree,
                        compilation -> compilation.requireExtension("GL_EXT_nonuniform_qualifier"));
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, declaration);
            });
            sources.put(stage, parser.transform(text));
        });
        var layout = shader.layout();
        var descriptors = new ArrayList<>(layout.descriptors());
        descriptors.add(new ShaderResourceLayout.Descriptor("_flw_textures", ShaderResourceLayout.Kind.SAMPLER,
                "SAMPLER2D", VkCaps.BINDLESS_TABLE_CAPACITY, SET, 0, null,
                Set.of(PatchShaderType.VERTEX, PatchShaderType.FRAGMENT)));
        return new PatchedShader(sources,
                new ShaderResourceLayout(layout.uniformBufferSize(), layout.uniforms(), descriptors,
                        layout.pushUniforms()), shader.samplers(), shader.writtenAttachments());
    }

    private record Stage(String source, boolean sampled, Map<String, String> varyings) {
    }

    /**
     * Device update-after-bind descriptor limits.
     */
    public record Limits(long stageSamplers, long setSamplers, long stageImages, long setImages,
                         long stageResources) {
    }

    /**
     * Iris storage-buffer mirrors of the slot tables. Render thread; once per frame, outside passes.
     */
    public static final class Tables implements AutoCloseable {
        private @Nullable GpuBuffer handles;
        private @Nullable GpuBuffer info;
        private int handlesVersion = -1;
        private int infoVersion = -1;

        private static GpuBuffer upload(@Nullable GpuBuffer buffer, String label, ByteBuffer data) {
            if (buffer == null || buffer.size() < data.remaining()) {
                if (buffer != null) Iris.getRenderBackend().retire(buffer::close);
                buffer = Iris.getRenderBackend().createStorageBuffer(label,
                        Math.max(Long.highestOneBit(data.remaining() - 1L) << 1, 1024L), null);
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, data.remaining()), data);
            return buffer;
        }

        public void refresh(TextureManager textureManager) {
            if (VkContext.isVulkanHost()) {
                VkBindlessTable.refresh(textureManager);
            } else {
                GlBindlessTable.refresh(textureManager);
            }
            GuestTextureInfo.update(textureManager);
            int slots = GuestTextureInfo.slots();
            if (!VkContext.isVulkanHost() && (handles == null || handlesVersion != GlBindlessTable.version()
                    || handles.size() < (long) slots * Long.BYTES)) {
                ByteBuffer data = ByteBuffer.allocateDirect(slots * Long.BYTES).order(ByteOrder.nativeOrder());
                for (int slot = BindlessSlots.FIRST_MATERIAL_SLOT; slot < slots; slot++) {
                    data.putLong(slot * Long.BYTES, GlBindlessTable.handle(slot));
                }
                handles = upload(handles, "flywheel guest texture handles", data);
                handlesVersion = GlBindlessTable.version();
            }
            if (info == null || infoVersion != GuestTextureInfo.version()) {
                int ints = slots * GuestTextureInfo.SLOT_INTS;
                ByteBuffer data = ByteBuffer.allocateDirect(ints * Integer.BYTES).order(ByteOrder.nativeOrder());
                data.asIntBuffer().put(GuestTextureInfo.data(), 0, ints);
                info = upload(info, "flywheel guest texture info", data);
                infoVersion = GuestTextureInfo.version();
            }
        }

        public void buffers(Map<String, List<GpuBufferSlice>> buffers) {
            if (info != null) buffers.put("_FlwGuestTextureInfo", List.of(info.slice()));
            if (handles != null) buffers.put("_FlwTextureHandles", List.of(handles.slice()));
        }

        @Override
        public void close() {
            if (handles != null) Iris.getRenderBackend().retire(handles::close);
            if (info != null) Iris.getRenderBackend().retire(info::close);
            handles = info = null;
        }
    }
}
