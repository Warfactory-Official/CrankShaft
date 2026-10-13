package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkNativePipelineCompiler;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkMeshPipeline;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns one ordinary native OIT render pass. Water, main and cloud producers keep distinct real depth
 * attachments; transitions and input reads remain framebuffer-local. Render thread only. Supplied views are
 * borrowed for recording, and every frontend facade must close before advancing to the next subpass.
 */
public final class VkNativeOitPass {
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("crankshaft.nativeOit.vulkan.subpasses", "true"));
    private static final int FRAMEBUFFER_STAGES = VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            | VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
            | VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    private static final int FRAMEBUFFER_READS = VK12.VK_ACCESS_INPUT_ATTACHMENT_READ_BIT
            | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK12.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
    private static final int FRAMEBUFFER_WRITES = VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    private static final int[] NO_ATTACHMENTS = new int[0];
    // Color attachments: B0 original bounds (emission pass: emission target), B1 culled bounds, C0-C3, A.
    // Depth attachments start at mainIndex.
    private static final int BOUNDS = 0;
    private static final int CULLED = 1;
    private static final int COEFFICIENT_0 = 2;
    private static final int ACCUMULATION = 6;
    private static final int[] COEFFICIENTS = {COEFFICIENT_0, COEFFICIENT_0 + 1, COEFFICIENT_0 + 2, COEFFICIENT_0 + 3};
    // B1, C0-C3 = input_attachment_index 0-4.
    private static final int[] EVALUATE_INPUTS = {CULLED, COEFFICIENT_0, COEFFICIENT_0 + 1, COEFFICIENT_0 + 2,
            COEFFICIENT_0 + 3};
    // Engine producer color slots, VkOitPipelines folded order: bounds, C0-C3, A.
    private static final int[] SLOT_BOUNDS = {0};
    private static final int[] SLOT_COEFFICIENTS = {1, 2, 3, 4};
    private static final int[] SLOT_ACCUMULATION = {5};
    private static final int[] SLOT_EMISSION = {0};
    // DEPTH_TERRAIN, DEPTH_MAIN precede the cull; B0 dies there.
    private static final int CULL_SUBPASS = 2;
    private static @Nullable VkNativeOitPass active;
    private static final Map<Spec, VkNativeOitPass> SPECS = new HashMap<>();
    private static final Map<Spec, VkNativeOitPass> EMISSION_SPECS = new HashMap<>();

    private final List<Node> nodes = new ArrayList<>();
    private final Runnable facadeClosed = () -> this.openFacades--;
    private long renderPass;
    private long framebuffer;
    private int[] formats = NO_ATTACHMENTS;
    private long[] framebufferViews = new long[0];
    private @Nullable Targets targets;
    private @Nullable BackendFactory backendFactory;
    private @Nullable VkCommandBuffer commandBuffer;
    private int mainIndex;
    private int waterIndex;
    private int cloudIndex;
    private int width;
    private int height;
    private int subpass = -1;
    private int openFacades;
    private boolean insert;
    private boolean customClouds;
    private boolean water;
    private boolean clouds;
    private boolean emission;

    public record Spec(int mainDepthFormat, boolean water, boolean clouds, boolean customClouds, boolean insert) {
    }

    public static Spec spec(Targets targets, boolean insert) {
        return new Spec(VulkanConst.toVk(targets.mainDepth().texture().getFormat()), targets.waterDepth() != null,
                targets.cloudDepth() != null, targets.customClouds(), insert);
    }

    public static boolean active() {
        return active != null;
    }

    /** Fused subpass graph, else separate native passes; engine folded producers need negotiated local reads. */
    public static boolean fused(VkPrograms programs) {
        return ENABLED && programs.localRead();
    }

    /** Loading/configuration warmup and runtime obtain the same render-pass compatibility objects. */
    public static VkNativeOitPass get(Spec spec) {
        return SPECS.computeIfAbsent(spec, key -> {
            VkNativeOitPass pass = new VkNativeOitPass();
            pass.prepareCompatibility(key.mainDepthFormat(), key.water(), key.clouds(), key.customClouds(),
                    key.insert());
            return pass;
        });
    }

    /** Separate pass, main features only: no vanilla terrain or cloud producer is additive. */
    public static VkNativeOitPass getEmission(int mainDepthFormat) {
        return EMISSION_SPECS.computeIfAbsent(new Spec(mainDepthFormat, false, false, false, false), key -> {
            VkNativeOitPass pass = new VkNativeOitPass();
            pass.emission = true;
            pass.prepareCompatibility(key.mainDepthFormat(), false, false, false, false);
            return pass;
        });
    }

    /** Call after native pipeline reset; no recorded producer may retain a borrowed facade. */
    public static void resetSpecs() {
        for (VkNativeOitPass pass : SPECS.values()) pass.delete();
        for (VkNativeOitPass pass : EMISSION_SPECS.values()) pass.delete();
        SPECS.clear();
        EMISSION_SPECS.clear();
    }

    /** World/frame-target teardown releases view-dependent objects while keeping warmed pipeline specs. */
    public static void releaseFramebuffers() {
        for (VkNativeOitPass pass : SPECS.values()) pass.deleteFramebuffer();
        for (VkNativeOitPass pass : EMISSION_SPECS.values()) pass.deleteFramebuffer();
    }

    /** No view is owned by this record or by the pass; its lifetime is the prepared level frame. */
    public record Targets(GpuTextureView originalBounds, GpuTextureView culledBounds,
                          GpuTextureView[] coefficients, @Nullable GpuTextureView accumulation,
                          GpuTextureView mainDepth, @Nullable GpuTextureView waterDepth,
                          @Nullable GpuTextureView cloudDepth, boolean customClouds,
                          @Nullable GpuTextureView emission) {
        public Targets {
            coefficients = coefficients.clone();
        }
    }

    /** Loader boundary: NeoForge adds the stencil flag to the native backend constructor. */
    @FunctionalInterface
    public interface BackendFactory {
        VulkanRenderPass create(VkCommandBuffer commandBuffer, RenderPass.RenderArea area, int width, int height,
                                boolean stencil, Supplier<String> label);
    }

    public enum Phase {
        DEPTH_TERRAIN("depth/terrain_water", OitMode.DEPTH_RANGE),
        DEPTH_MAIN("depth/main_features", OitMode.DEPTH_RANGE),
        CULL_BOUNDS("depth/copy_cull_main", OitMode.DEPTH_RANGE),
        DEPTH_CUSTOM_CLOUD("depth/custom_cloud_main", OitMode.DEPTH_RANGE),
        DEPTH_CLOUD("depth/cloud_seed_and_bounds", OitMode.DEPTH_RANGE),
        WATER_MASK("depth/water_rebuild", OitMode.DEPTH_RANGE),
        COEFFICIENT_TERRAIN("coefficients/terrain_water", OitMode.GENERATE_COEFFICIENTS),
        COEFFICIENT_MAIN("coefficients/main_features", OitMode.GENERATE_COEFFICIENTS),
        COEFFICIENT_CLOUD("coefficients/cloud", OitMode.GENERATE_COEFFICIENTS),
        EVALUATE_TERRAIN("evaluate/terrain_water", OitMode.EVALUATE),
        EVALUATE_MAIN("evaluate/main_features", OitMode.EVALUATE),
        EVALUATE_CLOUD("evaluate/cloud", OitMode.EVALUATE),
        INSERT_TERRAIN("insert/terrain_water", OitMode.EVALUATE),
        INSERT_MAIN("insert/main_features", OitMode.EVALUATE),
        INSERT_CLOUD("insert/cloud", OitMode.EVALUATE),
        EMISSION_MAIN("emission/main_features", OitMode.EVALUATE);

        private final String label;
        private final OitMode mode;

        Phase(String label, OitMode mode) {
            this.label = "flywheel:vk/native_oit/" + label;
            this.mode = mode;
        }

        public OitMode mode() {
            return mode;
        }

        public boolean emission() {
            return this == EMISSION_MAIN;
        }

        public boolean insert() {
            return this == INSERT_TERRAIN || this == INSERT_MAIN || this == INSERT_CLOUD;
        }

        /** Main-feature phases: engine instances and native features share their main depth. */
        public boolean main() {
            return this == DEPTH_MAIN || this == COEFFICIENT_MAIN || this == EVALUATE_MAIN || this == INSERT_MAIN
                    || this == EMISSION_MAIN;
        }
    }

    private static final class Node {
        final Phase phase;
        final int depth;
        final int[] colors;
        final int[] inputs;
        final int[] inputAspects;
        final int[] sourceColors;
        final GpuTextureView[] inputViews;
        VkGraphicsPipeline.Subpass engineCompatibility;
        List<ColorTargetState> ordinaryTargets;
        VkNativePipelineCompiler.Compatibility nativeCompatibility;

        Node(Phase phase, int depth, int[] colors, int[] inputs, int[] inputAspects, int[] sourceColors) {
            this.phase = phase;
            this.depth = depth;
            this.colors = colors;
            this.inputs = inputs;
            this.inputAspects = inputAspects;
            this.sourceColors = sourceColors;
            inputViews = new GpuTextureView[inputs.length];
        }

        boolean uses(int attachment) {
            if (depth == attachment) return true;
            for (int color : colors) if (color == attachment) return true;
            for (int input : inputs) if (input == attachment) return true;
            return false;
        }
    }

    /** Creates compatibility/framebuffer objects before recording, without acquiring ownership of any image. */
    private void prepare(Targets next, BackendFactory backendFactory, boolean insert, int width, int height) {
        if (commandBuffer != null) throw new IllegalStateException("Cannot prepare an active native OIT pass");
        long previousRenderPass = renderPass;
        boolean water = !emission && next.waterDepth() != null;
        boolean clouds = !emission && next.cloudDepth() != null;
        prepareCompatibility(VulkanConst.toVk(next.mainDepth().texture().getFormat()), water, clouds,
                clouds && next.customClouds(), insert);
        List<GpuTextureView> views = new ArrayList<>();
        views.add(emission ? next.emission() : next.originalBounds());
        if (!emission) views.add(next.culledBounds());
        if (!insert && !emission) {
            if (next.coefficients().length != 4) {
                throw new IllegalArgumentException("Wavelet requires four coefficient attachments");
            }
            views.addAll(Arrays.asList(next.coefficients()));
            views.add(next.accumulation());
        }
        mainIndex = views.size();
        views.add(next.mainDepth());
        waterIndex = water ? views.size() : mainIndex;
        if (water) views.add(next.waterDepth());
        cloudIndex = clouds ? views.size() : -1;
        if (clouds) views.add(next.cloudDepth());
        int[] nextFormats = new int[views.size()];
        long[] nextViews = new long[views.size()];
        for (int i = 0; i < views.size(); i++) {
            nextFormats[i] = VulkanConst.toVk(views.get(i).texture().getFormat());
            nextViews[i] = VkContext.imageView(views.get(i));
        }
        if (!Arrays.equals(formats, nextFormats)) {
            throw new IllegalArgumentException("Native OIT attachment formats differ from the warmed spec");
        }
        boolean graphChanged = previousRenderPass != renderPass;
        if (framebuffer == 0L || graphChanged || this.width != width || this.height != height
                || !Arrays.equals(framebufferViews, nextViews)) {
            deleteFramebuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                        .renderPass(renderPass).pAttachments(stack.longs(nextViews)).width(width).height(height)
                        .layers(1);
                var handle = stack.mallocLong(1);
                check(VK12.vkCreateFramebuffer(VkContext.vkDevice(), info, null, handle), "native OIT framebuffer");
                framebuffer = handle.get(0);
            }
            framebufferViews = nextViews;
        }
        this.targets = next;
        this.backendFactory = backendFactory;
        this.width = width;
        this.height = height;
        for (Node node : nodes) {
            for (int i = 0; i < node.inputs.length; i++) node.inputViews[i] = view(node.inputs[i]);
        }
    }

    /** Image-free compatibility creation; selected-algorithm PSOs can compile before the first world draw. */
    public void prepareCompatibility(int mainDepthFormat, boolean water, boolean clouds, boolean customClouds,
                                     boolean insert) {
        if (commandBuffer != null) throw new IllegalStateException("Cannot replace an active native OIT spec");
        mainIndex = emission ? BOUNDS + 1 : insert ? CULLED + 1 : ACCUMULATION + 1;
        waterIndex = water ? mainIndex + 1 : mainIndex;
        cloudIndex = clouds ? mainIndex + (water ? 2 : 1) : -1;
        int[] nextFormats = new int[mainIndex + 1 + (water ? 1 : 0) + (clouds ? 1 : 0)];
        Arrays.fill(nextFormats, VK12.VK_FORMAT_R16G16B16A16_SFLOAT);
        if (!emission) nextFormats[BOUNDS] = nextFormats[CULLED] = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
        nextFormats[mainIndex] = mainDepthFormat;
        if (water) nextFormats[waterIndex] = VK12.VK_FORMAT_D32_SFLOAT;
        if (clouds) nextFormats[cloudIndex] = VK12.VK_FORMAT_D32_SFLOAT;
        if (renderPass != 0L && Arrays.equals(formats, nextFormats) && this.water == water && this.clouds == clouds
                && this.customClouds == customClouds && this.insert == insert) return;
        deleteHandles();
        formats = nextFormats;
        this.water = water;
        this.clouds = clouds;
        this.customClouds = customClouds;
        this.insert = insert;
        buildNodes(customClouds);
        createRenderPass();
    }

    private void buildNodes(boolean customClouds) {
        nodes.clear();
        int[] bounds = {BOUNDS};
        if (emission) {
            add(Phase.EMISSION_MAIN, mainIndex, bounds, NO_ATTACHMENTS, SLOT_EMISSION);
            return;
        }
        int[] culled = {CULLED};
        add(Phase.DEPTH_TERRAIN, waterIndex, bounds, NO_ATTACHMENTS, SLOT_BOUNDS);
        add(Phase.DEPTH_MAIN, mainIndex, bounds, NO_ATTACHMENTS, SLOT_BOUNDS);
        add(Phase.CULL_BOUNDS, mainIndex, culled, bounds, SLOT_BOUNDS);
        if (cloudIndex >= 0) {
            if (customClouds) add(Phase.DEPTH_CUSTOM_CLOUD, mainIndex, culled, NO_ATTACHMENTS, SLOT_BOUNDS);
            add(Phase.DEPTH_CLOUD, cloudIndex, culled, new int[]{mainIndex}, SLOT_BOUNDS);
        }
        if (waterIndex != mainIndex) add(Phase.WATER_MASK, waterIndex, NO_ATTACHMENTS, new int[]{mainIndex},
                NO_ATTACHMENTS);
        if (insert) {
            add(Phase.INSERT_TERRAIN, waterIndex, NO_ATTACHMENTS, NO_ATTACHMENTS, NO_ATTACHMENTS);
            add(Phase.INSERT_MAIN, mainIndex, NO_ATTACHMENTS, NO_ATTACHMENTS, NO_ATTACHMENTS);
            if (cloudIndex >= 0) add(Phase.INSERT_CLOUD, cloudIndex, NO_ATTACHMENTS, NO_ATTACHMENTS, NO_ATTACHMENTS);
        } else {
            add(Phase.COEFFICIENT_TERRAIN, waterIndex, COEFFICIENTS, culled, SLOT_COEFFICIENTS);
            add(Phase.COEFFICIENT_MAIN, mainIndex, COEFFICIENTS, culled, SLOT_COEFFICIENTS);
            if (cloudIndex >= 0) add(Phase.COEFFICIENT_CLOUD, cloudIndex, COEFFICIENTS, culled, SLOT_COEFFICIENTS);
            int[] accumulation = {ACCUMULATION};
            add(Phase.EVALUATE_TERRAIN, waterIndex, accumulation, EVALUATE_INPUTS, SLOT_ACCUMULATION);
            add(Phase.EVALUATE_MAIN, mainIndex, accumulation, EVALUATE_INPUTS, SLOT_ACCUMULATION);
            if (cloudIndex >= 0) {
                add(Phase.EVALUATE_CLOUD, cloudIndex, accumulation, EVALUATE_INPUTS, SLOT_ACCUMULATION);
            }
        }
    }

    private void add(Phase phase, int depth, int[] colors, int[] inputs, int[] sourceColors) {
        int[] aspects = new int[inputs.length];
        for (int i = 0; i < inputs.length; i++) aspects[i] = inputs[i] >= mainIndex
                ? VK12.VK_IMAGE_ASPECT_DEPTH_BIT : VK12.VK_IMAGE_ASPECT_COLOR_BIT;
        nodes.add(new Node(phase, depth, colors, inputs, aspects, sourceColors));
    }

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription2.Buffer attachments = VkAttachmentDescription2.calloc(formats.length, stack);
            for (int i = 0; i < formats.length; i++) {
                boolean depth = i >= mainIndex;
                attachments.get(i).sType$Default().format(formats[i]).samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(i == mainIndex || i == waterIndex || emission && i == cloudIndex
                                ? VK12.VK_ATTACHMENT_LOAD_OP_LOAD
                                : !depth && i != CULLED ? VK12.VK_ATTACHMENT_LOAD_OP_CLEAR
                                : VK12.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .storeOp(i == BOUNDS && !emission ? VK12.VK_ATTACHMENT_STORE_OP_DONT_CARE
                        : VK12.VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(depth ? VK12.VK_ATTACHMENT_LOAD_OP_LOAD : VK12.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(depth ? VK12.VK_ATTACHMENT_STORE_OP_STORE
                        : VK12.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK12.VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            }
            VkSubpassDescription2.Buffer subpasses = VkSubpassDescription2.calloc(nodes.size(), stack);
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                VkAttachmentReference2.Buffer colors = references(stack, node.colors, null);
                VkAttachmentReference2.Buffer inputs = references(stack, node.inputs, node.inputAspects);
                VkAttachmentReference2 depth = VkAttachmentReference2.calloc(stack).sType$Default()
                        .attachment(node.depth).layout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                int[] preserves = new int[formats.length];
                int preserveCount = 0;
                for (int attachment = 0; attachment < formats.length; attachment++) {
                    // Every stored attachment but B0 remains live through pass completion.
                    if (!node.uses(attachment) && (emission || attachment != BOUNDS || i < CULL_SUBPASS)) {
                        preserves[preserveCount++] = attachment;
                    }
                }
                // LWJGL cannot infer the shared color/resolve attachment count from either pointer setter.
                subpasses.get(i).sType$Default().pipelineBindPoint(VK12.VK_PIPELINE_BIND_POINT_GRAPHICS)
                        .colorAttachmentCount(colors.remaining())
                        .pColorAttachments(colors).pInputAttachments(inputs).pDepthStencilAttachment(depth)
                        .pPreserveAttachments(stack.ints(Arrays.copyOf(preserves, preserveCount)));
            }
            VkSubpassDependency2.Buffer dependencies = VkSubpassDependency2.calloc(nodes.size(), stack);
            int storageAccess = insert ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT : 0;
            for (int i = 0; i < nodes.size(); i++) {
                dependencies.get(i).sType$Default()
                        .srcSubpass(i == 0 ? VK12.VK_SUBPASS_EXTERNAL : i - 1)
                        .dstSubpass(i)
                        .srcStageMask(FRAMEBUFFER_STAGES).dstStageMask(FRAMEBUFFER_STAGES)
                        .srcAccessMask(FRAMEBUFFER_READS | FRAMEBUFFER_WRITES | storageAccess)
                        .dstAccessMask(FRAMEBUFFER_READS | FRAMEBUFFER_WRITES | storageAccess)
                        .dependencyFlags(VK12.VK_DEPENDENCY_BY_REGION_BIT);
            }
            VkRenderPassCreateInfo2 info = VkRenderPassCreateInfo2.calloc(stack).sType$Default()
                    .pAttachments(attachments).pSubpasses(subpasses).pDependencies(dependencies);
            var handle = stack.mallocLong(1);
            check(VK12.vkCreateRenderPass2(VkContext.vkDevice(), info, null, handle), "native OIT render pass");
            renderPass = handle.get(0);
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                int[] colors = new int[node.colors.length];
                for (int c = 0; c < colors.length; c++) colors[c] = formats[node.colors[c]];
                node.engineCompatibility = new VkGraphicsPipeline.Subpass(renderPass, i, formats[node.depth],
                        colors, node.sourceColors);
                List<ColorTargetState> states = new ArrayList<>();
                Optional<BlendFunction> blend = Optional.of(node.phase.mode() == OitMode.DEPTH_RANGE
                        ? BlendFunction.MAX : BlendFunction.ADDITIVE);
                for (int color : node.colors) states.add(new ColorTargetState(blend,
                        formats[color] == VK12.VK_FORMAT_R32G32B32A32_SFLOAT ? GpuFormat.RGBA32_FLOAT
                        : GpuFormat.RGBA16_FLOAT, -1));
                node.ordinaryTargets = List.copyOf(states);
                node.nativeCompatibility =
                        new VkNativePipelineCompiler.Compatibility(renderPass, i, formats[node.depth],
                        states);
            }
        }
    }

    private static VkAttachmentReference2.Buffer references(MemoryStack stack, int[] indices,
            int @Nullable [] aspects) {
        VkAttachmentReference2.Buffer references = VkAttachmentReference2.calloc(indices.length, stack);
        for (int i = 0; i < indices.length; i++) references.get(i).sType$Default().attachment(indices[i])
                .layout(VK12.VK_IMAGE_LAYOUT_GENERAL).aspectMask(aspects == null ? 0 : aspects[i]);
        return references;
    }

    /** {@code prepare -> begin -> subpass per phase -> close}; closes on failure too. */
    public void record(Targets next, BackendFactory backendFactory, boolean insert, int width, int height, float far,
                       Consumer<VkNativeOitPass> subpass) {
        prepare(next, backendFactory, insert, width, height);
        begin(far);
        try {
            do {
                subpass.accept(this);
            } while (advance());
        } finally {
            close();
        }
    }

    private void begin(float far) {
        if (active != null) throw new IllegalStateException("Native OIT render passes cannot nest");
        commandBuffer = VkContext.beginCommands();
        VkContext.pushLabel(commandBuffer, emission ? "flywheel:vk/native_oit/emission"
                : "flywheel:vk/native_oit/fused_ordinary");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clears = VkClearValue.calloc(formats.length, stack);
            if (!emission) {
                clears.get(BOUNDS).color().float32(0, -far).float32(1, -far).float32(2, 0.0f).float32(3, 0.0f);
            }
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass).framebuffer(framebuffer).pClearValues(clears);
            info.renderArea().extent().set(width, height);
            VK12.vkCmdBeginRenderPass2(commandBuffer, info, VkSubpassBeginInfo.calloc(stack).sType$Default()
                    .contents(VK12.VK_SUBPASS_CONTENTS_INLINE));
        }
        active = this;
        subpass = 0;
        VkCmd.setViewportScissor(commandBuffer, width, height);
        installMarker();
        VkContext.pushLabel(commandBuffer, phase().label);
    }

    public Phase phase() {
        return nodes.get(subpass).phase;
    }

    public int phaseCount() {
        return nodes.size();
    }

    public Phase phase(int index) {
        return nodes.get(index).phase;
    }

    public VkNativePipelineCompiler.Compatibility compatibility(int index) {
        return nodes.get(index).nativeCompatibility;
    }

    public VkGraphicsPipeline.Subpass engineCompatibility(int index) {
        return nodes.get(index).engineCompatibility;
    }

    public VkCommandBuffer commandBuffer() {
        return commandBuffer;
    }

    private boolean advance() {
        requireClosedFacades();
        if (subpass + 1 == nodes.size()) return false;
        VkContext.popLabel(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdNextSubpass2(commandBuffer,
                    VkSubpassBeginInfo.calloc(stack).sType$Default().contents(VK12.VK_SUBPASS_CONTENTS_INLINE),
                    VkSubpassEndInfo.calloc(stack).sType$Default());
        }
        subpass++;
        installMarker();
        VkContext.pushLabel(commandBuffer, phase().label);
        return true;
    }

    private void installMarker() {
        VkContext.encoder().currentRenderPass = backend(() -> phase().label);
    }

    private VulkanRenderPass backend(Supplier<String> label) {
        return backendFactory.create(commandBuffer, new RenderPass.RenderArea(0, 0, width, height), width, height,
                VkContext.stencilFormat(formats[nodes.get(subpass).depth]) != VK12.VK_FORMAT_UNDEFINED, label);
    }

    /** Closing this facade never ends rendering; the pass owner advances or ends the one outer render pass. */
    public RenderPass borrow(Supplier<String> label) {
        Node node = nodes.get(subpass);
        var builder = RenderPassDescriptor.builder(label)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        for (int color : node.colors) builder.withColorAttachment(view(color));
        builder.withDepthAttachment(view(node.depth));
        var descriptor = builder.build();
        openFacades++;
        FrontendRenderPass pass =
                new FrontendRenderPass(backend(label), VkContext.device(), descriptor.colorAttachments(),
                true, facadeClosed, descriptor.renderArea());
        RenderSystem.bindDefaultUniforms(pass);
        return pass;
    }

    public VkNativePipelineCompiler.Compatibility compatibility(List<@Nullable ColorTargetState> colorTargets) {
        return new VkNativePipelineCompiler.Compatibility(renderPass, subpass, formats[nodes.get(subpass).depth],
                colorTargets);
    }

    public VkNativePipelineCompiler.Compatibility compatibility() {
        return nodes.get(subpass).nativeCompatibility;
    }

    public List<ColorTargetState> ordinaryColorTargets() {
        return nodes.get(subpass).ordinaryTargets;
    }

    public GpuTextureView input(int index) {
        return view(nodes.get(subpass).inputs[index]);
    }

    public GpuTextureView[] inputViews() {
        return nodes.get(subpass).inputViews;
    }

    private GpuTextureView view(int attachment) {
        if (attachment == BOUNDS) return emission ? targets.emission() : targets.originalBounds();
        if (attachment == CULLED) return targets.culledBounds();
        if (attachment == mainIndex) return targets.mainDepth();
        if (attachment == waterIndex) return targets.waterDepth();
        if (attachment == cloudIndex) return targets.cloudDepth();
        if (attachment == ACCUMULATION) return targets.accumulation();
        return targets.coefficients()[attachment - COEFFICIENT_0];
    }

    /** Explicitly used by OIT bind sites; ordinary pipeline handles and classic paths remain direct. */
    public static long enginePipeline(VkGraphicsPipeline pipeline) {
        return active == null ? pipeline.handle()
                : pipeline.subpassHandle(active.nodes.get(active.subpass).engineCompatibility);
    }

    public static long enginePipeline(VkMeshPipeline pipeline) {
        return active == null ? pipeline.handle()
                : pipeline.subpassHandle(active.nodes.get(active.subpass).engineCompatibility);
    }

    private void requireClosedFacades() {
        if (openFacades != 0) throw new IllegalStateException("Native producer retained a facade across subpasses");
    }

    private void close() {
        requireClosedFacades();
        VkContext.popLabel(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdEndRenderPass2(commandBuffer, VkSubpassEndInfo.calloc(stack).sType$Default());
        }
        VkContext.encoder().currentRenderPass = null;
        if (!insert) VkCmd.memoryBarrier(commandBuffer,
                VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                        | VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                        | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                FRAMEBUFFER_WRITES,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                        | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        VkContext.popLabel(commandBuffer);
        VkContext.submitCommands(commandBuffer);
        active = null;
        commandBuffer = null;
        subpass = -1;
        targets = null;
        backendFactory = null;
        for (Node node : nodes) Arrays.fill(node.inputViews, null);
    }

    public void delete() {
        if (commandBuffer != null) throw new IllegalStateException("Cannot delete an active native OIT pass");
        deleteHandles();
    }

    private void deleteHandles() {
        deleteFramebuffer();
        if (renderPass != 0L) {
            long old = renderPass;
            VkContext.deferDestroy(() -> VK12.vkDestroyRenderPass(VkContext.vkDevice(), old, null));
            renderPass = 0L;
        }
    }

    private void deleteFramebuffer() {
        if (framebuffer != 0L) {
            long old = framebuffer;
            VkContext.deferDestroy(() -> VK12.vkDestroyFramebuffer(VkContext.vkDevice(), old, null));
            framebuffer = 0L;
        }
    }

    private static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " creating " + operation);
        }
    }
}
