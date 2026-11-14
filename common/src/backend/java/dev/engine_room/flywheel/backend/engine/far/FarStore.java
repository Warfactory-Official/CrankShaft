package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.QuadIndexSequence;
import dev.engine_room.flywheel.backend.compile.FarPrograms;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.PipelineVersions;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.RenderPassUniforms;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL30.glBindBufferRange;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.glDispatchCompute;
import static org.lwjgl.opengl.GL43.glDispatchComputeIndirect;
import static org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect;

/**
 * Far visuals on the GPU. Rows (an instance preceded by its part slot) live in 32-row pages per instancer key in one
 * object buffer; entries (one kept visual each) and their parts (its rows sharing one pose) in stable slots. Once a
 * part's bounds are read back it is baked: its rows seen orthographically from the 6 faces of its box into an atlas
 * tile, which bricks that look alike share; a part past {@link #BRICK_BLOCKS} is split into bricks on a grid, each
 * its own box and slot. Each frame an entry pass picks every entry's level; mesh-level rows are culled into per-key
 * ranges and drawn by uber multi-draw, impostor-level bricks as ray-marched boxes. CPU work follows captures,
 * removals and readbacks only. Render thread.
 */
public final class FarStore {
    private static final int RECORD_UINTS = 1;
    private static final int PAGE_ROWS = 32;
    private static final int ENTRY_BYTES = 48;
    private static final int PART_BYTES = 192;
    private static final int TILE_BYTES = 64;
    private static final int JOB_BYTES = 32;
    private static final int GRID_BYTES = 48;
    private static final int MODEL_BYTES = 28;
    private static final int MODEL_BOUNDS_BYTES = 32;
    private static final int DRAW_BYTES = 48;
    private static final int FAR_BYTES = 128;
    private static final int TEXELS_PER_BLOCK = 16;
    private static final int MAX_TEXELS_PER_BLOCK = 64;
    // A face pair (2 x 256 by 256) leaves room for all three in the scratch.
    private static final int MAX_FACE_TEXELS = 256;
    // A part bakes as one box up to this size, so at no fewer than 16 texels per block; past it, bricks of at most this
    // size (doubled while over MAX_BRICKS), all at one density within MAX_PART_TEXELS.
    private static final int BRICK_BLOCKS = MAX_FACE_TEXELS / TEXELS_PER_BLOCK;
    // A brick mask per row is 2 uints.
    private static final int MAX_BRICKS = 64;
    // One atlas layer.
    private static final int MAX_PART_TEXELS = FarAtlas.LAYER_SIZE * FarAtlas.LAYER_SIZE;
    private static final int SINGLE_GRID = 1 | 1 << 8 | 1 << 16;
    private static final int SCRATCH_SIZE = 1024;
    private static final int MAX_CANDIDATES = 4;
    private static final int MAX_ROUNDS = 16;
    private static final int PART_LIVE = 1;
    private static final int PART_BAKED = 2;
    private static final int PART_OWNED_MAX_SHIFT = 2;
    private static final int UBO_FAR = 9;
    private static final int UBO_INSTANCE_DRAW = 11;
    private static final int UBO_EMBED_DRAW = 12;
    // Storage bindings, as internal/far declares them.
    private static final int SSBO_INPUT = 0;
    private static final int SSBO_OBJECTS = 1;
    private static final int SSBO_INDICES = 2;
    private static final int SSBO_MODELS = 3;
    private static final int SSBO_DRAWS = 4;
    private static final int SSBO_LEVELS = 5;
    private static final int SSBO_LIGHT_LUT = 5;
    private static final int SSBO_LIGHT_SECTIONS = 6;
    private static final int SSBO_PARTS = 15;
    private static final int SSBO_MODEL_BOUNDS = 7;
    private static final int SSBO_ENTRIES = 9;
    private static final int SSBO_JOBS = 10;
    private static final int SSBO_IMPOSTORS = 11;
    private static final int SSBO_TILES = 12;
    private static final int SSBO_RESULTS = 13;
    private static final int SSBO_COMPARES = 14;
    private static final int SSBO_BRICK_GRIDS = 10;
    private static final int SSBO_BRICK_BOUNDS = 13;
    private static final int SSBO_BRICK_MASKS = 14;
    private static final int UNIT_SCRATCH_DEPTH = 12;
    private static final int UNIT_ATLAS_COLOR = 13;
    private static final int UNIT_ATLAS_AUX = 14;
    private static final int UNIT_ATLAS_NORMAL = 15;
    private static final int UNIT_ATLAS_MATERIAL = 16;
    private static final int EMPTY_MIN = sortable(Float.POSITIVE_INFINITY);
    private static final int EMPTY_MAX = sortable(Float.NEGATIVE_INFINITY);
    private static final Comparator<Draw> DRAW_ORDER = Comparator.comparingInt((Draw draw) -> draw.batch.bias)
                                                                 .thenComparingInt(Draw::meshIndex)
                                                                 .thenComparing(Draw::material,
                                                                         MaterialRenderState::uberPipelineCompare)
                                                                 .thenComparing(Draw::material,
                                                                         MaterialRenderState.COMPARATOR)
                                                                 .thenComparingInt(draw -> draw.batch.typeId);

    private final MeshPool meshPool = new MeshPool();
    private final RenderPassUniforms renderPassUniforms = new RenderPassUniforms();
    private final FarAtlas atlas = new FarAtlas();
    private final FarReadback readback = new FarReadback();
    private @Nullable FarLayers layers;
    private final List<Brick> pendingLayers = new ArrayList<>();
    private boolean layeredProducerReady;
    private boolean frameConstantAmbientLight;
    private final FarBuffer objects = new FarBuffer();
    private final FarBuffer pages = new FarBuffer();
    private final FarBuffer models = new FarBuffer();
    private final FarBuffer modelBounds = new FarBuffer();
    private final FarBuffer indices = new FarBuffer();
    private final FarBuffer draws = new FarBuffer();
    private final FarBuffer entries = new FarBuffer();
    private final FarBuffer levels = new FarBuffer();
    private final FarBuffer parts = new FarBuffer();
    private final FarBuffer tiles = new FarBuffer();
    private final FarBuffer newRows = new FarBuffer();
    private final FarBuffer newPartBounds = new FarBuffer();
    private final FarBuffer jobs = new FarBuffer();
    private final FarBuffer brickRows = new FarBuffer();
    private final FarBuffer brickGrids = new FarBuffer();
    private final FarBuffer brickBounds = new FarBuffer();
    private final FarBuffer brickMasks = new FarBuffer();
    private final FarBuffer bakeIndices = new FarBuffer();
    private final FarBuffer bakeDraws = new FarBuffer();
    private final FarBuffer opaqueBounds = new FarBuffer();
    private final FarBuffer impostors = new FarBuffer();
    // DrawArraysIndirect, then the next mesh dispatch and the resident row-page group count.
    private final FarBuffer impostorCommand = new FarBuffer();
    private final FarBuffer compares = new FarBuffer();
    private final FarBuffer results = new FarBuffer();
    private final FarBuffer far = new FarBuffer();
    // A light lookup table holding no section.
    private final FarBuffer noLight = new FarBuffer();
    private final Map<BatchKey, Batch> batchByKey = new HashMap<>();
    private final List<Batch> batches = new ArrayList<>();
    // Per page, as the cull pass reads them: batch index, valid bits, first instance uint, type id | stride << 16.
    private final IntArrayList pageWords = new IntArrayList();
    private final Long2ObjectOpenHashMap<Entry> entryByPos = new Long2ObjectOpenHashMap<>();
    private final IntArrayList freeEntrySlots = new IntArrayList();
    private final IntArrayList freePartSlots = new IntArrayList();
    private final IntArrayList freeTileSlots = new IntArrayList();
    // Bounds pass input: objectUint, batch index, type id, index in newParts per row added since the last render.
    private final IntArrayList pendingRows = new IntArrayList();
    private final List<Part> newParts = new ArrayList<>();
    // Parts past BRICK_BLOCKS waiting for the brick pass.
    private final List<Grid> gridded = new ArrayList<>();
    // Bricks with their bake box, in capture order.
    private final ArrayDeque<Brick> bakeQueue = new ArrayDeque<>();
    private final Map<Appearance, List<Tile>> appearances = new HashMap<>();
    private final List<Draw> drawList = new ArrayList<>();
    private final List<Run> runs = new ArrayList<>();
    private final Map<Material, Integer> translucentMaterials = new LinkedHashMap<>();
    private InstanceTypeIds.@Nullable Snapshot computeSnapshot;
    private ProgramGeneration required = ProgramGeneration.NONE;
    private @Nullable RenderPipeline impostorPipeline;
    private @Nullable GpuTexture scratchColor;
    private @Nullable GpuTexture scratchDepth;
    private @Nullable GpuTexture scratchNormal;
    private @Nullable GpuTexture scratchMaterial;
    private @Nullable GpuTextureView scratchColorView;
    private @Nullable GpuTextureView scratchDepthView;
    private int entrySlots;
    private int partSlots;
    private int tileSlots;
    private int bakedParts;
    private int bakedBricks;
    private float reach;
    private int objectUints;
    private boolean pagesDirty;
    private boolean drawsDirty;
    // This frame's _FlwFar block but the count.
    private int tick;
    private float partialTick;
    private float meshScale;
    private int surfaceNormal;
    private final Matrix4f drawProjection = new Matrix4f();
    private final Matrix4f occlusionClip = new Matrix4f();
    private final Map<RenderPipeline, FarGlDraw> rawPipelines = new HashMap<>();
    private final GpuBufferSlice[] vertexSlices = new GpuBufferSlice[16];
    private @Nullable RenderContext drawFrame;
    private @Nullable GpuBufferSlice projectionSlice;
    private @Nullable GpuBufferSlice fogSlice;
    private @Nullable GpuBufferSlice lightingSlice;
    private @Nullable GpuBufferSlice transformSlice;
    private SceneOcclusion scene;
    private SceneOcclusion.@Nullable View occlusion;
    private int rawFramebuffer;
    private int drawWidth;
    private int drawHeight;
    private int occlusionWidth;
    private int occlusionHeight;
    private int occlusionLevels;
    private int occlusionMode = SceneOcclusion.AUTO;
    private boolean depthCulling;
    private int surfaceLight;
    private int surfaceMaterial;
    private final Vector4f camera = new Vector4f();

    public FarStore() {
        far.ensure(FAR_BYTES);
        impostorCommand.ensure(32);
        noLight.ensure(16);
        noLight.write(0, new int[4], 4);
    }

    /**
     * The device can run far visuals: the GL compute and multi-draw path with its programs loaded.
     */
    public static boolean supported() {
        return FarPrograms.get() != null && IndirectPrograms.allLoaded();
    }

    /**
     * Host orchestration supplies complete producer/resolve readiness before this frame's prepare. Render thread.
     */
    public void layeredProducerReady(boolean ready) {
        layeredProducerReady = ready;
    }

    public boolean hasLayered() {
        return layers != null;
    }

    /**
     * Layered source storage contract, all bindings borrowed until the caller restores its host scope.
     * The shared GLSL hit record is 112 bytes (seven std430 vec4/uvec4 slots), defined by layer_storage.glsl.
     */
    public record LayerBindings(int descriptors, int rectangles, int surfaces, int tiles, int parts,
                                int firstTextureUnit, int hitBytes) {
    }

    public static final int LAYER_BACKFACE_CULL = 1;
    public static final int LAYER_POLYGON_OFFSET = 2;
    public static final int LAYER_DEPTH_TEST_SHIFT = 2;
    public static final int LAYER_TRANSPARENCY_SHIFT = 8;
    public static final int LAYER_DEPTH_WRITE = 1 << 16;
    public static final int LAYER_COLOR_WRITE = 1 << 17;

    public static LayerBindings layeredBindings() {
        return new LayerBindings(0, 3, 7, 12, 15, 10, 112);
    }

    /**
     * Published brick. IDs in a source window are one-based; surfaceCount bounds hits per pixel without truncation.
     */
    public record LayerPart(int partSlot, int surfaceCount, int ranks, int validMipMask,
                            int x, int y, int width, int height) {
        public LayerPart(int partSlot, int surfaceCount, int ranks, int validMipMask) {
            this(partSlot, surfaceCount, ranks, validMipMask, 0, 0, 0, 0);
        }
    }

    /**
     * Visits resident layered bricks selected at the impostor tier this frame. The GPU box shader also tests
     * selection; source windows are conservative upper bounds, independent of the chosen final OIT budget.
     */
    public void forEachLayeredPart(Consumer<LayerPart> consumer) {
        for (var entry : entryByPos.values()) {
            for (var part : entry.parts) {
                visitLayeredPart(part, consumer);
                for (var brick : part.others) visitLayeredPart(brick, consumer);
            }
        }
    }

    /**
     * Conservative animated screen rectangles, clipped to the explicit producer viewport. No GPU readback.
     */
    public void forEachLayeredPart(FarLayerView view, Consumer<LayerPart> consumer) {
        for (var entry : entryByPos.values()) {
            for (var part : entry.parts) {
                visitLayeredRect(part, view, consumer);
                for (var brick : part.others) visitLayeredRect(brick, view, consumer);
            }
        }
    }

    private void visitLayeredRect(Brick brick, FarLayerView view, Consumer<LayerPart> consumer) {
        if (brick.layerTile == null || !brick.baked) return;
        var part = brick.part;
        var rect = projectedBounds(part, view, brick.boxMin[0], brick.boxMin[1], brick.boxMin[2],
                brick.boxMin[0] + brick.extent[0] / part.density,
                brick.boxMin[1] + brick.extent[1] / part.density,
                brick.boxMin[2] + brick.extent[2] / part.density);
        if (rect == null) return;
        var tile = brick.layerTile;
        consumer.accept(new LayerPart(brick.slot, tile.sourceCount(), tile.ranks(), tile.validMips(),
                rect.x, rect.y, rect.width, rect.height));
    }

    private record PixelRect(int x, int y, int width, int height) {
    }

    private @Nullable PixelRect projectedBounds(Part part, FarLayerView view, float boxLoX, float boxLoY, float boxLoZ,
                                                float boxHiX, float boxHiY, float boxHiZ) {
        float angle = part.phase + part.radiansPerTick * (tick - part.startTick + partialTick);
        float loX = Float.POSITIVE_INFINITY, loY = Float.POSITIVE_INFINITY;
        float hiX = Float.NEGATIVE_INFINITY, hiY = Float.NEGATIVE_INFINITY;
        int positiveCorners = 0;
        boolean crossesEye = false;
        var position = new Vector3f();
        var clip = new Vector4f();
        for (int corner = 0; corner < 8; corner++) {
            position.set((corner & 1) == 0 ? boxLoX : boxHiX,
                    (corner & 2) == 0 ? boxLoY : boxHiY, (corner & 4) == 0 ? boxLoZ : boxHiZ);
            if (part.spins) position.sub(part.pivot).rotateAxis(angle, part.axis.x, part.axis.y, part.axis.z)
                                    .add(part.pivot);
            position.add(part.entry.origin.getX() - view.renderOrigin().getX(),
                    part.entry.origin.getY() - view.renderOrigin().getY(),
                    part.entry.origin.getZ() - view.renderOrigin().getZ());
            clip.set(position, 1.0f).mul(view.viewProjection());
            if (clip.w <= 0) crossesEye = true;
            else {
                positiveCorners++;
                float x = clip.x / clip.w, y = clip.y / clip.w;
                loX = Math.min(loX, x);
                loY = Math.min(loY, y);
                hiX = Math.max(hiX, x);
                hiY = Math.max(hiY, y);
            }
        }
        if (positiveCorners == 0) return null;
        int x0 = crossesEye ? 0 : Math.clamp((int) Math.floor((loX * 0.5 + 0.5) * view.width()) - 1, 0, view.width());
        int y0 = crossesEye ? 0 : Math.clamp((int) Math.floor((loY * 0.5 + 0.5) * view.height()) - 1, 0, view.height());
        int x1 = crossesEye ? view.width()
                : Math.clamp((int) Math.ceil((hiX * 0.5 + 0.5) * view.width()) + 1, 0, view.width());
        int y1 = crossesEye ? view.height()
                : Math.clamp((int) Math.ceil((hiY * 0.5 + 0.5) * view.height()) + 1, 0, view.height());
        if (x1 <= x0 || y1 <= y0) return null;
        return new PixelRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static void visitLayeredPart(Brick brick, Consumer<LayerPart> consumer) {
        if (brick.layerTile != null && brick.baked) {
            var tile = brick.layerTile;
            consumer.accept(new LayerPart(brick.slot, tile.sourceCount(), tile.ranks(), tile.validMips()));
        }
    }

    /**
     * Binds layer_sample.glsl storage, arrays and explicit view uniforms to an already published producer.
     * No framebuffer, blend/depth state, opaque snapshot or radius policy is installed here. Render thread.
     */
    public void bindLayeredSource(GlProgram program, FarLayerView view) {
        layers.bind(program);
        parts.bindStorage(15);
        tiles.bindStorage(12);
        levels.bindStorage(5);
        far.bindUniform(9);
        bindLayerView(program, view);
    }

    private static void bindLayerView(GlProgram program,
                                      FarLayerView view) {
        program.bind();
        program.setMat4("_flw_farLayerViewProjection", view.viewProjection());
        GL20C.glUniform3i(program.getUniformLocation("_flw_farLayerRenderOrigin"),
                view.renderOrigin().getX(), view.renderOrigin().getY(), view.renderOrigin().getZ());
        program.setVec3("_flw_farLayerCamera", (float) (view.camera().x - view.renderOrigin().getX()),
                (float) (view.camera().y - view.renderOrigin().getY()),
                (float) (view.camera().z - view.renderOrigin().getZ()));
        program.setBool("_flw_farLayerZeroToOne", view.zeroToOne());
        boolean hardwareZeroToOne = view.hardwareZeroToOne();
        program.setVec2("_flw_farLayerDepthTransform", view.zeroToOne() == hardwareZeroToOne ? 1.0f
                        : hardwareZeroToOne ? 0.5f : 2.0f,
                view.zeroToOne() == hardwareZeroToOne ? 0.0f : hardwareZeroToOne ? 0.5f : -1.0f);
        var matrix = view.viewProjection();
        program.setFloat("_flw_farLayerPixelScale", (float) Math.sqrt(matrix.m01() * matrix.m01()
                + matrix.m11() * matrix.m11() + matrix.m21() * matrix.m21()) * view.height() * 0.5f);
    }

    /**
     * Draws one front box surface per ray; installs backface culling inside the caller's borrowed state scope.
     */
    public void drawLayeredBoxes(GlProgram program, FarLayerView view) {
        bindLayeredSource(program, view);
        layers.bindVertexArray();
        GlStateManager._enableCull();
        GL11C.glFrontFace(GL11C.GL_CCW);
        impostors.bindStorage(11);
        program.setBool("_flw_farLayerSingle", false);
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(impostorCommand.handle());
        GL40C.glDrawArraysIndirect(GL_TRIANGLES, 0);
    }

    /**
     * One published brick for bounded pixel/source-window adapters; selection is still checked on the GPU.
     */
    public void drawLayeredBox(GlProgram program, FarLayerView view,
                               int partSlot) {
        bindLayeredSource(program, view);
        layers.bindVertexArray();
        GlStateManager._enableCull();
        GL11C.glFrontFace(GL11C.GL_CCW);
        program.setBool("_flw_farLayerSingle", true);
        program.setUInt("_flw_farLayerPart", partSlot);
        GL11C.glDrawArrays(GL_TRIANGLES, 0, 36);
    }

    /**
     * Near-tier source batch. Offsets address the existing 48-byte indirect command ABI and preserve source order.
     * The consumer supplies its selected native/Iris transparency program and explicit producer view/state.
     */
    public record MeshBatch(Material material, GpuBuffer vertexBuffer,
                            GpuBuffer indexBuffer, int commandBuffer,
                            long commandOffset, int drawCount, int objectBuffer, int indexBufferTable,
                            int partBuffer, int drawBuffer, RenderPipeline inputPipeline,
                            ProgramGeneration required, int maximumInstances, int primitiveCount,
                            int modelBatch, int x, int y, int width, int height) {
    }

    /**
     * Visits authored translucent materials before GPU preparation, for async producer preflight.
     */
    public void forEachTranslucentMaterial(Consumer<Material> consumer) {
        translucentMaterials.keySet().forEach(consumer);
    }

    /**
     * Near-tier material batches with conservative current-pose rectangles from asynchronous source bounds.
     */
    public void forEachTranslucentMesh(FarLayerView view, Consumer<MeshBatch> consumer) {
        forEachTranslucentMesh(batch -> {
            PixelRect merged = null;
            for (var part : batches.get(batch.modelBatch()).liveParts) {
                if (!part.hasBounds || part.meshOnly) {
                    merged = new PixelRect(0, 0, view.width(), view.height());
                    break;
                }
                var rect = projectedBounds(part, view, part.boundsLo[0], part.boundsLo[1], part.boundsLo[2],
                        part.boundsHi[0], part.boundsHi[1], part.boundsHi[2]);
                if (rect == null) continue;
                if (merged == null) merged = rect;
                else {
                    int x = Math.min(merged.x, rect.x), y = Math.min(merged.y, rect.y);
                    int hiX = Math.max(merged.x + merged.width, rect.x + rect.width);
                    int hiY = Math.max(merged.y + merged.height, rect.y + rect.height);
                    merged = new PixelRect(x, y, hiX - x, hiY - y);
                }
            }
            if (merged == null) return;
            consumer.accept(new MeshBatch(batch.material(), batch.vertexBuffer(), batch.indexBuffer(),
                    batch.commandBuffer(), batch.commandOffset(), batch.drawCount(), batch.objectBuffer(),
                    batch.indexBufferTable(), batch.partBuffer(), batch.drawBuffer(), batch.inputPipeline(),
                    batch.required(), batch.maximumInstances(), batch.primitiveCount(), batch.modelBatch(),
                    merged.x, merged.y, merged.width, merged.height));
        });
    }

    public void forEachTranslucentMesh(Consumer<MeshBatch> consumer) {
        if (computeSnapshot == null || meshPool.vertexBuffer() == null || meshPool.indexBuffer() == null) return;
        for (Run run : runs) {
            if (!layered(run.material) || !run.coverage.covers(run.required)) continue;
            // One material per command keeps texture/sampler and authored pass semantics explicit.
            for (int i = run.start; i < run.end; i++) {
                var draw = drawList.get(i);
                if (draw.batch.liveRows == 0) continue;
                consumer.accept(new MeshBatch(draw.material, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                        draws.handle(), (long) i * DRAW_BYTES, 1, objects.handle(), indices.handle(), parts.handle(),
                        draws.handle(), run.pipelines[0], draw.required, draw.batch.liveRows,
                        draw.mesh.indexCount() / 3, draw.batch.index, 0, 0, 0, 0));
            }
        }
    }

    /**
     * Binds a near-tier batch's original mesh/row/part inputs and fixed engine bindings. The published program must
     * bind _FlwRenderOrigin at UBO10, _FlwInstanceDraw at11 and _FlwEmbedDraw at12 during link setup. The caller owns
     * projection/dynamic-transform/fog/light blocks, target/OIT state and material samplers. Render thread.
     */
    public void bindTranslucentMeshSource(GlProgram program,
                                          MeshBatch batch, FarLayerView view) {
        var input = rawPipelines.get(batch.inputPipeline());
        vertexSlices[0] = batch.vertexBuffer().slice();
        input.vertices(vertexSlices);
        input.indices(batch.indexBuffer());
        bindLayerView(program, view);
        if (batch.material().backfaceCulling()) GlStateManager._enableCull();
        else GlStateManager._disableCull();
        bindUniform(0, projectionSlice);
        bindUniform(1, transformSlice);
        bindUniform(2, fogSlice);
        bindUniform(3, lightingSlice);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, batch.objectBuffer());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, batch.indexBufferTable());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, batch.drawBuffer());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 15, batch.partBuffer());
        noLight.bindStorage(5);
        noLight.bindStorage(6);
        far.bindUniform(9);
        bindUniform(10, renderPassUniforms.renderOriginSlice());
        bindUniform(11, renderPassUniforms.material(MaterialEncoder.packProperties(batch.material())));
        bindUniform(12, renderPassUniforms.embedDraw((int) (batch.commandOffset() / DRAW_BYTES)));
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) GlBindlessTable.bind();
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(batch.commandBuffer());
    }

    /**
     * Publish callback setup for the source-specific near-tier uniform blocks.
     */
    public static void setupTranslucentMeshProgram(GlProgram program) {
        program.setUniformBlockBinding("Projection", 0);
        program.setUniformBlockBinding("DynamicTransforms", 1);
        program.setUniformBlockBinding("Fog", 2);
        program.setUniformBlockBinding("Lighting", 3);
        program.setUniformBlockBinding("_FlwRenderOrigin", 10);
        program.setUniformBlockBinding("_FlwInstanceDraw", 11);
        program.setUniformBlockBinding("_FlwEmbedDraw", 12);
    }

    /**
     * Binds the original source inputs; projection/model-view slices and material samplers belong to the producer.
     */
    public void bindTranslucentMesh(GlProgram program,
                                    MeshBatch batch, FarLayerView view) {
        bindTranslucentMeshSource(program, batch, view);
    }

    public int size() {
        return entryByPos.size();
    }

    public boolean contains(long pos) {
        return entryByPos.containsKey(pos);
    }

    /**
     * Live parts every brick of which is baked.
     */
    public int bakedParts() {
        return bakedParts;
    }

    /**
     * Baked parts of the retained entry at {@code pos}. Render thread.
     */
    public int bakedParts(long pos) {
        Entry entry = entryByPos.get(pos);
        return entry.parts.length - entry.unbaked;
    }

    /**
     * Baked geometry and distinct appearance tiles of an existing entry. Render thread.
     */
    public EntryStats entryStats(long pos) {
        Entry entry = entryByPos.get(pos);
        var tileSlots = new IntOpenHashSet();
        int bricks = 0;
        int nativeRows = 0;
        for (var part : entry.parts) {
            if (part.meshOnly) nativeRows += part.rows.length;
            if (part.baked) {
                bricks++;
                tileSlots.add(part.tile.slot);
            }
            for (var brick : part.others) {
                if (brick.baked) {
                    bricks++;
                    tileSlots.add(brick.tile.slot);
                }
            }
        }
        return new EntryStats(entry.parts.length - entry.unbaked, bricks, tileSlots.size(), nativeRows);
    }

    public record EntryStats(int parts, int bricks, int tiles, int nativeRows) {
    }

    /**
     * Baked bricks of live parts: the impostor boxes.
     */
    public int bakedBricks() {
        return bakedBricks;
    }

    /**
     * The farthest any part's box has reached horizontally from its entry's block centre since the store last
     * emptied, in blocks.
     */
    public float reach() {
        return reach;
    }

    /**
     * Atlas tiles: the appearances baked bricks share.
     */
    public int tiles() {
        return tileSlots - freeTileSlots.size();
    }

    /**
     * Keeps {@code rows}, relative to {@code origin}, as the entry at {@code pos}, replacing one there, its parts lit
     * at least by {@code light} (block | sky << 16, 0-240 each).
     */
    public void add(long pos, Vec3i origin, List<FarRow> rows, int light) {
        remove(pos);
        if (rows.isEmpty()) {
            return;
        }
        Map<Pose, List<FarRow>> poses = new LinkedHashMap<>();
        boolean meshOnly = false;
        for (FarRow row : rows) {
            poses.computeIfAbsent(Pose.of(row), _ -> new ArrayList<>()).add(row);
            meshOnly |= !row.snapshot().terrain();
        }
        Entry entry = new Entry(freeEntrySlots.isEmpty() ? entrySlots++ : freeEntrySlots.popInt(), poses.size(), pos,
                origin, meshOnly);
        entryByPos.put(pos, entry);
        int index = 0;
        for (List<FarRow> pose : poses.values()) {
            Part part = addPart(entry, origin, pose, light);
            entry.parts[index++] = part;
            entry.meshSwitch = Math.max(entry.meshSwitch, part.meshSwitch);
        }
        entries.ensure((long) entrySlots * ENTRY_BYTES);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer record = stack.malloc(ENTRY_BYTES);
            record.putInt(origin.getX()).putInt(origin.getY()).putInt(origin.getZ()).putInt(meshOnly ? 3 : 1)
                  .putInt(EMPTY_MIN).putInt(EMPTY_MIN).putInt(EMPTY_MIN).putFloat(entry.meshSwitch)
                  .putInt(EMPTY_MAX).putInt(EMPTY_MAX).putInt(EMPTY_MAX).putInt(0);
            entries.write((long) entry.slot * ENTRY_BYTES, MemoryUtil.memAddress0(record), ENTRY_BYTES);
        }
    }

    public void remove(long pos) {
        Entry entry = entryByPos.remove(pos);
        if (entry == null) {
            return;
        }
        for (Part part : entry.parts) {
            for (int handle : part.rows) {
                int page = handle >>> 5;
                var batch = batches.get(pageWords.getInt(page * 4));
                batch.liveRows--;
                for (var material : batch.materials) {
                    if (!layered(material)) continue;
                    int references = translucentMaterials.get(material) - 1;
                    if (references == 0) translucentMaterials.remove(material);
                    else translucentMaterials.put(material, references);
                }
                int valid = pageWords.getInt(page * 4 + 1);
                if (valid == -1) {
                    batches.get(pageWords.getInt(page * 4)).open.push(page);
                }
                pageWords.set(page * 4 + 1, valid & ~(1 << (handle & 31)));
            }
            for (int batchIndex : part.batchIndices) batches.get(batchIndex).liveParts.remove(part);
            part.removed = true;
            if (part.unbaked == 0) {
                bakedParts--;
            }
            drop(part);
            for (Brick brick : part.others) {
                drop(brick);
                freePartSlots.push(brick.slot);
            }
            freePartSlots.push(part.slot);
        }
        pagesDirty = true;
        writeInt(entries, (long) entry.slot * ENTRY_BYTES + 12, 0);
        freeEntrySlots.push(entry.slot);
        if (entryByPos.isEmpty()) {
            reach = 0;
        }
    }

    /**
     * Prepares entries of {@code frame}, seen through {@code projection}, baking parts whose bounds arrived.
     * Blaze3D's projection, fog and lighting are the caller's; culling and drawing follow the host's opaque terrain.
     * Render thread, outside any render pass.
     */
    public void prepare(GpuTextureView color, GpuTextureView depth, RenderContext frame, Matrix4f projection,
                        Matrix4fc modelView, Vec3i origin, boolean constantAmbientLight, int tick, float partialTick,
                        float meshScale, int surfaceNormal, int surfaceLight, int surfaceMaterial) {
        scene = SceneOcclusion.of(frame.camera().entity().level());
        scene.projection(projection);
        depthCulling = false;
        FarPrograms programs = FarPrograms.get();
        readback.poll();
        if (layers != null) layers.poll();
        meshPool.flush();
        if (drawsDirty) {
            rebuildDraws();
            uploadModelBounds();
            uploadModels();
            drawsDirty = false;
        }
        if (pagesDirty) {
            pages.ensure((long) pageWords.size() * 4);
            pages.write(0, pageWords.elements(), pageWords.size());
            writeInts(impostorCommand, 16, 0, 1, 1, pageWords.size() / 4);
            pagesDirty = false;
        }
        levels.ensure((long) entrySlots * 4);
        impostors.ensure((long) partSlots * 4);
        frameConstantAmbientLight = constantAmbientLight;
        renderPassUniforms.beginFrame(origin, constantAmbientLight);
        renderPassUniforms.material(0);
        for (int i = 0; i < drawList.size(); i++) {
            var draw = drawList.get(i);
            renderPassUniforms.material(MaterialEncoder.packProperties(draw.material));
            renderPassUniforms.embedDraw(i);
        }
        Vec3 eye = frame.camera().position();
        this.tick = tick;
        this.partialTick = partialTick;
        this.meshScale = meshScale;
        this.surfaceNormal = surfaceNormal;
        this.surfaceLight = surfaceLight;
        this.surfaceMaterial = surfaceMaterial;
        drawFrame = frame;
        drawWidth = color.texture().getWidth(0);
        drawHeight = color.texture().getHeight(0);
        drawProjection.set(projection);
        occlusionClip.set(projection).mul(modelView);
        projectionSlice = RenderSystem.getProjectionMatrixBuffer();
        fogSlice = RenderSystem.getShaderFog();
        lightingSlice = RenderSystem.getShaderLights();
        transformSlice = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView));
        camera.set((float) (eye.x - origin.getX()), (float) (eye.y - origin.getY()), (float) (eye.z - origin.getZ()),
                projection.m11() * color.texture().getHeight(0) * 0.5f);

        computeSnapshot = programs.prepare();
        int cullTypes = computeSnapshot == null ? 0 : computeSnapshot.types().size();
        boolean bakeReady = computeSnapshot != null && cullTypes >= required.types();
        rawPipelines.keySet().removeIf(pipeline -> !RenderPipelineCompiler.ready(pipeline));
        for (Run run : runs) {
            run.pipelines = IndirectPipeline.farPipelinesFor(run.material, surfaceNormal != 0);
            run.coverage = run.pipelines == null ? ProgramGeneration.NONE
                    : PipelineVersions.generation(run.pipelines[0]).culledBy(cullTypes);
            if (layered(run.material)) {
                run.capture = FarLayerPipeline.get(run.material);
                bakeReady &= run.capture != null
                        && PipelineVersions.generation(run.capture).culledBy(cullTypes).covers(run.required);
                if (run.capture != null) rawPipelines.computeIfAbsent(run.capture, FarGlDraw::new);
            }
            bakeReady &= run.coverage.covers(run.required);
            if (run.pipelines != null) rawPipelines.computeIfAbsent(run.pipelines[0], FarGlDraw::new);
        }
        impostorPipeline = FarImpostorPipeline.get(surfaceNormal != 0);
        if (impostorPipeline != null) rawPipelines.computeIfAbsent(impostorPipeline, FarGlDraw::new);
        bakeReady &= impostorPipeline != null;
        if (layers != null) bakeReady &= programs.prepareLayers() && layeredProducerReady;
        if (bakeReady) finishLayers(programs);
        if (bakeReady && (!newParts.isEmpty() || !gridded.isEmpty() || !bakeQueue.isEmpty())) {
            Uniforms.bindAll();
            FrameUniforms.bindView(frame, projection);
            try {
                bounds(programs);
                bricks(programs);
                bake(programs);
            } finally {
                FrameUniforms.bind();
            }
        }
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (Draw draw : drawList) textureManager.getTexture(draw.material.texture());
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) GlBindlessTable.refresh(textureManager);
        long entryWork = (long) entryByPos.size() * 256;
        long brickWork = 36L * bakedBricks;
        scene.farWork(Math.max(entryWork, brickWork), occlusionMode);
    }

    /**
     * Culls and draws against this frame's completed opaque host depth, inside the host's GL work.
     */
    public void draw(FarDepth source, int color, int depth) {
        if (computeSnapshot == null) {
            try (var _ = FarGlState.capture()) {
                occlusionWidth = source.width();
                occlusionHeight = source.height();
                drawRaw(color, depth);
            }
            return;
        }
        GlCompat.pushDebugGroup("far/render");
        try (var saved = FarGlState.capture()) {
            occlusionWidth = source.width();
            occlusionHeight = source.height();
            occlusion = scene.capture(source);
            depthCulling = occlusionMode != SceneOcclusion.FORCE_OFF && occlusion != null;
            if (depthCulling) {
                occlusionLevels = occlusion.levels();
                occlusion.bind();
            }
            var programs = FarPrograms.get();
            Uniforms.bindAll();
            FrameUniforms.bindView(drawFrame, drawProjection);
            GlCompat.pushDebugGroup("far/select_entries");
            try {
                writeFar(entrySlots);
                programs.entries().bind();
                levels.bindStorage(SSBO_LEVELS);
                entries.bindStorage(SSBO_ENTRIES);
                models.bindStorage(SSBO_MODELS);
                impostorCommand.bindStorage(SSBO_RESULTS);
                glDispatchCompute((Math.max(entrySlots, batches.size()) + 63) / 64, 1, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
            } finally {
                GlCompat.popDebugGroup();
            }

            GlCompat.pushDebugGroup("far/cull_rows");
            try {
                programs.cull(computeSnapshot).bind();
                pages.bindStorage(SSBO_INPUT);
                objects.bindStorage(SSBO_OBJECTS);
                indices.bindStorage(SSBO_INDICES);
                levels.bindStorage(SSBO_LEVELS);
                parts.bindStorage(SSBO_PARTS);
                GL15C.glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, impostorCommand.handle());
                glDispatchComputeIndirect(16);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            } finally {
                GlCompat.popDebugGroup();
            }

            GlCompat.pushDebugGroup("far/apply_counts");
            try {
                IndirectPrograms.get().getApplyProgram().bind();
                draws.bindStorage(SSBO_DRAWS);
                glDispatchCompute(GlCompat.getComputeGroupCount(drawList.size()), 1, 1);
            } finally {
                GlCompat.popDebugGroup();
            }

            GlCompat.pushDebugGroup("far/select_parts");
            try {
                programs.parts().bind();
                levels.bindStorage(SSBO_LEVELS);
                parts.bindStorage(SSBO_PARTS);
                impostors.bindStorage(SSBO_IMPOSTORS);
                tiles.bindStorage(SSBO_TILES);
                glDispatchCompute((partSlots + 63) / 64, 1, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
            } finally {
                GlCompat.popDebugGroup();
            }
            GlStateTracker.invalidateEncoderProgramCache();
            GlCompat.pushDebugGroup("far/draw_targets");
            try {
                drawRaw(color, depth);
            } finally {
                GlCompat.popDebugGroup();
            }
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    /**
     * Enables the opaque terrain test for subsequent frames; useful for comparing its submission cost.
     */
    public void occlusionEnabled(boolean enabled) {
        occlusionMode = enabled ? SceneOcclusion.FORCE_ON : SceneOcclusion.FORCE_OFF;
    }

    /**
     * Restores combined workload-based terrain HiZ demand after an explicit comparison override.
     */
    public void occlusionAuto() {
        occlusionMode = SceneOcclusion.AUTO;
    }

    /**
     * Reads the most recent GPU submission counts on the render thread.
     */
    public Submitted submitted() {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(Math.max(drawList.size() * (DRAW_BYTES / 4), 4));
            int rows = 0;
            if (!drawList.isEmpty()) {
                values.limit(drawList.size() * (DRAW_BYTES / 4));
                GL45C.glGetNamedBufferSubData(draws.handle(), 0, values);
                for (int i = 0; i < drawList.size(); i++) rows += values.get(i * (DRAW_BYTES / 4) + 4 / 4);
            }
            values.clear().limit(4);
            GL45C.glGetNamedBufferSubData(impostorCommand.handle(), 0, values);
            return new Submitted(rows, values.get(1));
        }
    }

    public record Submitted(int meshRows, int impostors) {
    }

    /**
     * This frame's occlusion inputs for renderer diagnostics, owned by the store. Render thread.
     */
    public OcclusionView occlusionView() {
        return new OcclusionView(occlusion.texture(), new Matrix4f(occlusionClip), occlusionWidth,
                occlusionHeight, occlusionLevels);
    }

    public record OcclusionView(int texture, Matrix4fc clip, int width, int height, int levels) {
    }

    public void delete() {
        for (Batch batch : batches) {
            batch.meshes.forEach(MeshPool.PooledMesh::release);
        }
        batches.clear();
        batchByKey.clear();
        translucentMaterials.clear();
        meshPool.delete();
        renderPassUniforms.delete();
        atlas.delete();
        if (layers != null) layers.close();
        pendingLayers.clear();
        readback.delete();
        if (rawFramebuffer != 0) GL30C.glDeleteFramebuffers(rawFramebuffer);
        for (FarBuffer buffer : List.of(objects, pages, models, modelBounds, indices, draws, entries, levels, parts,
                tiles, newRows, newPartBounds, jobs, brickRows, brickGrids, brickBounds, brickMasks, bakeIndices,
                bakeDraws, opaqueBounds, impostors, impostorCommand, compares, results, far, noLight)) {
            buffer.delete();
        }
        if (scratchColor != null) {
            scratchColorView.close();
            scratchDepthView.close();
            scratchColor.close();
            scratchDepth.close();
            scratchNormal.close();
            scratchMaterial.close();
        }
    }

    private Part addPart(Entry entry, Vec3i origin, List<FarRow> rows, int light) {
        int slot = freePartSlots.isEmpty() ? partSlots++ : freePartSlots.popInt();
        int[] handles = new int[rows.size()];
        int[] batchIndices = new int[rows.size()];
        int block = light & 0xFFFF;
        int sky = light >>> 16;
        float meshSwitch = 0;
        for (int i = 0; i < rows.size(); i++) {
            FarRow row = rows.get(i);
            Batch batch = batch(row.snapshot());
            batch.liveRows++;
            for (var material : batch.materials) {
                if (layered(material)) translucentMaterials.merge(material, 1, Integer::sum);
            }
            meshSwitch = Math.max(meshSwitch, batch.meshSwitch);
            int handle = allocate(batch);
            handles[i] = handle;
            batchIndices[i] = batch.index;
            block = Math.max(block, row.snapshot().light() & 0xFFFF);
            sky = Math.max(sky, row.snapshot().light() >>> 16);
            int objectUint = objectUint(handle);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer buffer = stack.malloc(batch.strideUints * 4);
                buffer.putInt(slot).put(row.snapshot().data());
                objects.write((long) (objectUint - RECORD_UINTS) * 4, MemoryUtil.memAddress0(buffer),
                        buffer.position());
            }
            pendingRows.add(objectUint);
            pendingRows.add(batch.index);
            pendingRows.add(batch.typeId);
            pendingRows.add(newParts.size());
        }
        Arrays.sort(batchIndices);
        Part part = new Part(slot, entry, handles, batchIndices, meshSwitch, rows.get(0).radiansPerTick() != 0,
                entry.meshOnly);
        for (int batchIndex : batchIndices) {
            batches.get(batchIndex).liveParts.add(part);
            for (var material : batches.get(batchIndex).materials) part.layered |= layered(material);
        }
        if (part.layered && layers == null) layers = new FarLayers();
        newParts.add(part);

        FarRow pose = rows.get(0);
        part.pivot.set(pose.pivot().x(), pose.pivot().y(), pose.pivot().z());
        part.axis.set(pose.axis().x(), pose.axis().y(), pose.axis().z());
        part.radiansPerTick = pose.radiansPerTick();
        part.phase = pose.phase();
        part.startTick = pose.startTick();
        parts.ensure((long) partSlots * PART_BYTES);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer record = stack.malloc(PART_BYTES);
            record.putFloat(pose.pivot().x()).putFloat(pose.pivot().y()).putFloat(pose.pivot().z())
                  .putFloat(pose.radiansPerTick())
                  .putFloat(pose.axis().x()).putFloat(pose.axis().y()).putFloat(pose.axis().z())
                  .putFloat(pose.phase())
                  .putInt(origin.getX()).putInt(origin.getY()).putInt(origin.getZ()).putInt(pose.startTick())
                  .putInt(entry.slot).putInt(-1).putInt(block | sky << 16)
                  .putInt(PART_LIVE | part.ownedMax << PART_OWNED_MAX_SHIFT)
                  .putFloat(0).putFloat(0).putFloat(0).putInt(-1);
            putFrame(record, pose.snapshot());
            parts.write((long) slot * PART_BYTES, MemoryUtil.memAddress0(record), PART_BYTES);
        }
        return part;
    }

    private static void putFrame(ByteBuffer record, FarInstance instance) {
        var pose = instance.pose();
        var normal = instance.normal();
        record.putFloat(pose.m00()).putFloat(pose.m01()).putFloat(pose.m02()).putFloat(pose.m03());
        record.putFloat(pose.m10()).putFloat(pose.m11()).putFloat(pose.m12()).putFloat(pose.m13());
        record.putFloat(pose.m20()).putFloat(pose.m21()).putFloat(pose.m22()).putFloat(pose.m23());
        record.putFloat(pose.m30()).putFloat(pose.m31()).putFloat(pose.m32()).putFloat(pose.m33());
        record.putFloat(normal.m00()).putFloat(normal.m01()).putFloat(normal.m02()).putFloat(radiusScale(pose));
        record.putFloat(normal.m10()).putFloat(normal.m11()).putFloat(normal.m12()).putFloat(0);
        record.putFloat(normal.m20()).putFloat(normal.m21()).putFloat(normal.m22()).putFloat(0);
    }

    // sqrt(||M||1 * ||M||infinity) bounds the maximum stretch, including nonuniform scales and shear.
    private static float radiusScale(Matrix4fc pose) {
        double m00 = Math.abs((double) pose.m00()), m01 = Math.abs((double) pose.m01()), m02 = Math.abs(
                (double) pose.m02());
        double m10 = Math.abs((double) pose.m10()), m11 = Math.abs((double) pose.m11()), m12 = Math.abs(
                (double) pose.m12());
        double m20 = Math.abs((double) pose.m20()), m21 = Math.abs((double) pose.m21()), m22 = Math.abs(
                (double) pose.m22());
        double column = Math.max(m00 + m01 + m02, Math.max(m10 + m11 + m12, m20 + m21 + m22));
        double row = Math.max(m00 + m10 + m20, Math.max(m01 + m11 + m21, m02 + m12 + m22));
        return Math.nextUp((float) Math.sqrt(column * row));
    }

    // Grows the bounds of entries and parts by their rows added since the last render, then reads the parts' back.
    private void bounds(FarPrograms programs) {
        if (newParts.isEmpty()) {
            return;
        }
        List<Part> bounded = List.copyOf(newParts);
        newParts.clear();
        IntArrayList rows = new IntArrayList(pendingRows.size());
        for (int i = 0; i < pendingRows.size(); i += 4) {
            if (!bounded.get(pendingRows.getInt(i + 3)).removed) {
                rows.addElements(rows.size(), pendingRows.elements(), i, 4);
            }
        }
        pendingRows.clear();
        int[] empty = new int[bounded.size() * 8];
        for (int i = 0; i < empty.length; i += 8) {
            Arrays.fill(empty, i, i + 4, EMPTY_MIN);
            Arrays.fill(empty, i + 4, i + 8, EMPTY_MAX);
        }
        newPartBounds.ensure(empty.length * 4L);
        newPartBounds.write(0, empty, empty.length);
        int rowCount = rows.size() / 4;
        if (rowCount > 0) {
            newRows.ensure(rows.size() * 4L);
            newRows.write(0, rows.elements(), rows.size());
            writeFar(rowCount);
            programs.bounds(computeSnapshot).bind();
            newRows.bindStorage(SSBO_INPUT);
            objects.bindStorage(SSBO_OBJECTS);
            models.bindStorage(SSBO_MODELS);
            modelBounds.bindStorage(SSBO_MODEL_BOUNDS);
            parts.bindStorage(SSBO_PARTS);
            entries.bindStorage(SSBO_ENTRIES);
            newPartBounds.bindStorage(SSBO_JOBS);
            glDispatchCompute(GlCompat.getComputeGroupCount(rowCount), 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        readback.request(newPartBounds.handle(), 0, empty.length * 4, data -> {
            for (int i = 0; i < bounded.size(); i++) {
                Part part = bounded.get(i);
                if (!part.removed) {
                    box(part, data, i * 32);
                }
            }
        });
    }

    // A part's read-back rest-pose bounds: up to BRICK_BLOCKS its box is its one brick, at texels per block from its
    // mesh switch within the face limit; past it, a grid waits for the brick pass. Empty bounds bake nothing.
    private void box(Part part, ByteBuffer bounds, int offset) {
        float[] lo = new float[3];
        float[] hi = new float[3];
        float size = 0;
        for (int a = 0; a < 3; a++) {
            lo[a] = unsortable(bounds.getInt(offset + a * 4));
            hi[a] = unsortable(bounds.getInt(offset + 16 + a * 4));
            if (!(hi[a] >= lo[a])) {
                return;
            }
            size = Math.max(size, hi[a] - lo[a]);
        }
        System.arraycopy(lo, 0, part.boundsLo, 0, 3);
        System.arraycopy(hi, 0, part.boundsHi, 0, 3);
        part.hasBounds = true;
        extendReach(part, lo, hi);
        if (part.meshOnly) return;
        if (size <= BRICK_BLOCKS) {
            part.density = Math.min(part.fine(), MAX_FACE_TEXELS / Math.max(size, 1f / TEXELS_PER_BLOCK));
            part.fit(lo, hi, part.density, 0, SINGLE_GRID);
            part.unbaked = 1;
            bakeQueue.add(part);
            coarsen(part.entry, part.density);
            return;
        }
        float span = BRICK_BLOCKS;
        int[] count = new int[3];
        do {
            for (int a = 0; a < 3; a++) {
                count[a] = Math.max(1, (int) Math.ceil((hi[a] - lo[a]) / span - 1e-3f));
            }
            span *= 2;
        } while (count[0] * count[1] * count[2] > MAX_BRICKS);
        float[] brick = new float[3];
        for (int a = 0; a < 3; a++) {
            brick[a] = (hi[a] - lo[a]) / count[a];
        }
        gridded.add(new Grid(part, lo, brick, count));
    }

    // The rows of gridded parts into their bricks, read back: each brick a row reaches gets the rows' boxes clipped to
    // it and the rows that reach it.
    private void bricks(FarPrograms programs) {
        List<Grid> grids = new ArrayList<>();
        for (Grid grid : gridded) {
            if (!grid.part.removed) {
                grids.add(grid);
            }
        }
        gridded.clear();
        if (grids.isEmpty()) {
            return;
        }
        IntArrayList rows = new IntArrayList();
        int[] gridWords = new int[grids.size() * GRID_BYTES / 4];
        int brickCount = 0;
        for (int j = 0; j < grids.size(); j++) {
            Grid grid = grids.get(j);
            int at = j * GRID_BYTES / 4;
            for (int a = 0; a < 3; a++) {
                gridWords[at + a] = Float.floatToRawIntBits(grid.origin[a]);
                gridWords[at + 4 + a] = Float.floatToRawIntBits(grid.size[a]);
                gridWords[at + 8 + a] = grid.count[a];
            }
            gridWords[at + 7] = Float.floatToRawIntBits(grid.part.layered ? 1.0f : 0.0f);
            gridWords[at + 11] = brickCount;
            brickCount += grid.bricks();
            for (int handle : grid.part.rows) {
                Batch batch = batches.get(pageWords.getInt((handle >>> 5) * 4));
                rows.add(objectUint(handle));
                rows.add(batch.index);
                rows.add(batch.typeId);
                rows.add(j);
            }
        }
        int[] empty = new int[brickCount * 8];
        for (int i = 0; i < empty.length; i += 8) {
            Arrays.fill(empty, i, i + 4, EMPTY_MIN);
            Arrays.fill(empty, i + 4, i + 8, EMPTY_MAX);
        }
        int rowCount = rows.size() / 4;
        brickRows.ensure(rows.size() * 4L);
        brickRows.write(0, rows.elements(), rows.size());
        brickGrids.ensure(gridWords.length * 4L);
        brickGrids.write(0, gridWords, gridWords.length);
        brickBounds.ensure(empty.length * 4L);
        brickBounds.write(0, empty, empty.length);
        brickMasks.ensure(rowCount * 32L);
        writeFar(rowCount);
        programs.bricks(computeSnapshot).bind();
        brickRows.bindStorage(SSBO_INPUT);
        objects.bindStorage(SSBO_OBJECTS);
        modelBounds.bindStorage(SSBO_MODEL_BOUNDS);
        parts.bindStorage(SSBO_PARTS);
        brickGrids.bindStorage(SSBO_BRICK_GRIDS);
        brickBounds.bindStorage(SSBO_BRICK_BOUNDS);
        brickMasks.bindStorage(SSBO_BRICK_MASKS);
        glDispatchCompute(GlCompat.getComputeGroupCount(rowCount), 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        int[] masks = new int[rowCount * 2];
        readback.request(brickMasks.handle(), 0, rowCount * 32, data -> {
            data.asIntBuffer().get(masks);
            int rowBase = 0;
            for (Grid grid : grids) {
                if (!grid.part.removed && grid.part.layered) {
                    var bounds = new float[grid.part.rows.length * 6];
                    for (int word = 0; word < bounds.length; word++)
                        bounds[word] = data.getFloat(rowCount * 8 + rowBase * 24 + word * 4);
                    grid.part.layerRowBounds = bounds;
                }
                rowBase += grid.part.rows.length;
            }
        });
        readback.request(brickBounds.handle(), 0, brickCount * 32, data -> {
            int brickBase = 0;
            int rowBase = 0;
            for (Grid grid : grids) {
                if (!grid.part.removed) {
                    split(grid, data, brickBase, masks, rowBase);
                }
                brickBase += grid.bricks();
                rowBase += grid.part.rows.length;
            }
        });
    }

    // A gridded part's reached bricks, each boxed to its clipped bounds, all at the part's texels per block lowered
    // until they fit MAX_PART_TEXELS; the part's own slot holds the first.
    private void split(Grid grid, ByteBuffer bounds, int brickBase, int[] masks, int rowBase) {
        Part part = grid.part;
        List<float[]> los = new ArrayList<>();
        List<float[]> his = new ArrayList<>();
        IntArrayList indices = new IntArrayList();
        float size = 1f / TEXELS_PER_BLOCK;
        for (int b = 0; b < grid.bricks(); b++) {
            float[] lo = new float[3];
            float[] hi = new float[3];
            boolean reached = true;
            for (int a = 0; a < 3; a++) {
                lo[a] = unsortable(bounds.getInt((brickBase + b) * 32 + a * 4));
                hi[a] = unsortable(bounds.getInt((brickBase + b) * 32 + 16 + a * 4));
                reached &= hi[a] >= lo[a];
            }
            if (reached) {
                los.add(lo);
                his.add(hi);
                indices.add(b);
                for (int a = 0; a < 3; a++) {
                    size = Math.max(size, hi[a] - lo[a]);
                }
            }
        }
        float density = Math.min(part.fine(), MAX_FACE_TEXELS / size);
        while (texels(los, his, density) > MAX_PART_TEXELS) {
            density *= 0.75f;
        }
        part.density = density;
        Brick[] bricks = new Brick[indices.size()];
        for (int k = 0; k < bricks.length; k++) {
            int b = indices.getInt(k);
            IntArrayList reaching = new IntArrayList();
            for (int r = 0; r < part.rows.length; r++) {
                if ((masks[(rowBase + r) * 2 + (b >>> 5)] >>> (b & 31) & 1) != 0) {
                    reaching.add(part.rows[r]);
                }
            }
            Brick brick = k == 0 ? part : new Brick(freePartSlots.isEmpty() ? partSlots++ : freePartSlots.popInt(),
                    part);
            brick.bakeRows = reaching.toIntArray();
            brick.fit(los.get(k), his.get(k), density, b, grid.code());
            brick.ownedMax = 0;
            int cell = b;
            for (int axis = 0; axis < 3; axis++) {
                int coordinate = cell % grid.count[axis];
                cell /= grid.count[axis];
                float boundary = grid.origin[axis] + (coordinate + 1) * grid.size[axis];
                if (coordinate + 1 == grid.count[axis] || his.get(k)[axis] < boundary)
                    brick.ownedMax |= 1 << axis;
                else brick.clipExtent[axis] = Math.min(brick.extent[axis],
                        (boundary - brick.boxMin[axis]) * density);
            }
            if (brick != part) {
                // Pose, entry and light as its part; no tile, live, not baked: the part has not been baked either.
                parts.ensure((long) partSlots * PART_BYTES);
                GL45C.glCopyNamedBufferSubData(parts.handle(), parts.handle(), (long) part.slot * PART_BYTES,
                        (long) brick.slot * PART_BYTES, PART_BYTES);
            }
            bricks[k] = brick;
        }
        part.others = Arrays.copyOfRange(bricks, 1, bricks.length);
        part.unbaked = bricks.length;
        bakeQueue.addAll(Arrays.asList(bricks));
        coarsen(part.entry, density);
    }

    // A spinning part's box turns about its pivot, taken to lie inside it: its diagonal bounds the swing.
    private void extendReach(Part part, float[] lo, float[] hi) {
        double centerX = BlockPos.getX(part.entry.pos) + 0.5 - part.entry.origin.getX();
        double centerZ = BlockPos.getZ(part.entry.pos) + 0.5 - part.entry.origin.getZ();
        double farthest = Math.hypot(Math.max(centerX - lo[0], hi[0] - centerX),
                Math.max(centerZ - lo[2], hi[2] - centerZ));
        if (part.spins) {
            farthest += Math.sqrt(Math.pow(hi[0] - lo[0], 2) + Math.pow(hi[1] - lo[1], 2) + Math.pow(hi[2] - lo[2], 2));
        }
        reach = Math.max(reach, (float) farthest);
    }

    private static long texels(List<float[]> los, List<float[]> his, float density) {
        long texels = 0;
        for (int k = 0; k < los.size(); k++) {
            int[] e = new int[3];
            for (int a = 0; a < 3; a++) {
                e[a] = extent(his.get(k)[a] - los.get(k)[a], density);
            }
            texels += 2L * (e[0] * e[1] + e[1] * e[2] + e[2] * e[0]);
        }
        return texels;
    }

    // Texels along a span in whole atlas units.
    private static int extent(float span, float density) {
        int texels = (int) Math.ceil(span * density - 0.01f);
        return Math.clamp((texels + FarAtlas.ALIGN - 1) / FarAtlas.ALIGN * FarAtlas.ALIGN, FarAtlas.ALIGN,
                MAX_FACE_TEXELS);
    }

    private void bake(FarPrograms programs) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (!bakeRound(programs)) {
                return;
            }
        }
    }

    // Queued bricks while they fit the scratch: a brick of a known appearance is probed (its scratch view compared with
    // that appearance's tiles, a match taken once read back, a miss queued again for a tile of its own), any other is
    // baked into a new tile with its levels. False once nothing was queued.
    private boolean bakeRound(FarPrograms programs) {
        List<Job> full = new ArrayList<>();
        List<Job> probes = new ArrayList<>();
        Scratch scratch = new Scratch();
        boolean layerReserved = false;
        while (!bakeQueue.isEmpty()) {
            Brick brick = bakeQueue.peek();
            if (brick.part.removed) {
                bakeQueue.poll();
                continue;
            }
            boolean needsLayers = brick.part.layered && brick.layerTile == null && brick.layerBake == null;
            if (needsLayers && (layerReserved || !layers.available())) break;
            int[] placed = scratch.place(brick.extent);
            if (placed == null) {
                break;
            }
            bakeQueue.poll();
            layerReserved |= needsLayers;
            List<Tile> known = brick.probed ? null : appearances.get(brick.key);
            if (known != null) {
                List<Tile> candidates = List.copyOf(known.subList(0, Math.min(known.size(), MAX_CANDIDATES)));
                candidates.forEach(candidate -> candidate.refs++);
                probes.add(new Job(brick, candidates.getFirst(), placed, candidates));
            } else {
                Tile tile = tile(brick);
                if (tile != null) {
                    full.add(new Job(brick, tile, placed, List.of()));
                } else if (needsLayers) {
                    bakeQueue.addFirst(brick);
                    throw new IllegalStateException("Far layered bake cannot reserve its opaque bounds tile");
                }
            }
        }
        List<Job> round = new ArrayList<>(full);
        round.addAll(probes);
        if (round.isEmpty()) {
            return false;
        }

        int[] counts = new int[batches.size()];
        for (Job job : round) {
            for (int handle : job.brick.bakeRows) {
                counts[pageWords.getInt((handle >>> 5) * 4)] += 6;
            }
        }
        int[] bases = new int[batches.size()];
        int items = 0;
        for (int i = 0; i < counts.length; i++) {
            bases[i] = items;
            items += counts[i];
        }
        int[] fill = bases.clone();
        int[] itemWords = new int[items * 2];
        long jobBytes = (long) round.size() * JOB_BYTES;
        jobs.ensure(jobBytes);
        long jobPtr = MemoryUtil.nmemAlloc(jobBytes);
        try {
            for (int j = 0; j < round.size(); j++) {
                Job job = round.get(j);
                Brick brick = job.brick;
                for (int handle : brick.bakeRows) {
                    int at = fill[pageWords.getInt((handle >>> 5) * 4)];
                    for (int face = 0; face < 6; face++) {
                        itemWords[(at + face) * 2] = objectUint(handle);
                        itemWords[(at + face) * 2 + 1] = j << 3 | face;
                    }
                    fill[pageWords.getInt((handle >>> 5) * 4)] = at + 6;
                }
                // A probe maps through its first candidate: same extent and density.
                long at = jobPtr + (long) j * JOB_BYTES;
                MemoryUtil.memPutInt(at, job.scratch[0]);
                MemoryUtil.memPutInt(at + 4, job.scratch[1]);
                MemoryUtil.memPutInt(at + 8, job.scratch[2]);
                MemoryUtil.memPutInt(at + 12, brick.slot);
                MemoryUtil.memPutInt(at + 16, job.tile.slot);
                MemoryUtil.memPutInt(at + 20, 0);
                MemoryUtil.memPutInt(at + 24, 0);
                MemoryUtil.memPutInt(at + 28, 0);
                writeInt(parts, (long) brick.slot * PART_BYTES + 60,
                        PART_LIVE | brick.ownedMax << PART_OWNED_MAX_SHIFT);
                writeInts(parts, (long) brick.slot * PART_BYTES + 64, Float.floatToRawIntBits(brick.boxMin[0]),
                        Float.floatToRawIntBits(brick.boxMin[1]), Float.floatToRawIntBits(brick.boxMin[2]));
            }
            jobs.write(0, jobPtr, jobBytes);
        } finally {
            MemoryUtil.nmemFree(jobPtr);
        }
        bakeIndices.ensure(itemWords.length * 4L);
        bakeIndices.write(0, itemWords, itemWords.length);
        writeDraws(bakeDraws, counts, bases);
        int[] opaqueWords = new int[round.size() * 8];
        for (int at = 0; at < opaqueWords.length; at += 8) {
            Arrays.fill(opaqueWords, at, at + 4, EMPTY_MIN);
            Arrays.fill(opaqueWords, at + 4, at + 8, EMPTY_MAX);
        }
        opaqueBounds.ensure(opaqueWords.length * 4L, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
        opaqueBounds.write(0, opaqueWords, opaqueWords.length);
        bakeRows();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        for (Job job : round) {
            if (job.brick.part.layered && job.brick.layerTile == null && job.brick.layerBake == null) {
                captureLayers(job, programs);
            }
        }
        jobs.bindStorage(SSBO_JOBS);

        if (!full.isEmpty()) {
            programs.bakeFinish().bind();
            jobs.bindStorage(SSBO_JOBS);
            tiles.bindStorage(SSBO_TILES);
            bindScratch();
            FarAtlas.bindImage(1, atlas.color(), 0, GL15C.GL_WRITE_ONLY, false);
            FarAtlas.bindImage(2, atlas.aux(), 0, GL15C.GL_WRITE_ONLY, true);
            FarAtlas.bindImage(4, atlas.normal(), 0, GL15C.GL_WRITE_ONLY, false);
            atlas.bindMaterialImage(6, 0, GL15C.GL_WRITE_ONLY);
            glDispatchCompute(full.size() * 6, 1, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

            programs.bakeMip().bind();
            for (int level = 1; level < FarAtlas.LEVELS; level++) {
                writeFar(level);
                FarAtlas.bindImage(0, atlas.color(), level - 1, GL15C.GL_READ_ONLY, false);
                FarAtlas.bindImage(1, atlas.color(), level, GL15C.GL_WRITE_ONLY, false);
                FarAtlas.bindImage(2, atlas.aux(), level - 1, GL15C.GL_READ_ONLY, true);
                FarAtlas.bindImage(3, atlas.aux(), level, GL15C.GL_WRITE_ONLY, true);
                FarAtlas.bindImage(4, atlas.normal(), level - 1, GL15C.GL_READ_ONLY, false);
                FarAtlas.bindImage(5, atlas.normal(), level, GL15C.GL_WRITE_ONLY, false);
                atlas.bindMaterialImage(6, level - 1, GL15C.GL_READ_ONLY);
                atlas.bindMaterialImage(7, level, GL15C.GL_WRITE_ONLY);
                glDispatchCompute(full.size() * 6, 1, 1);
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
            for (Job job : full) {
                writeInt(parts, (long) job.brick.slot * PART_BYTES + 52, job.tile.slot);
                baked(job.brick);
            }
        }
        if (!probes.isEmpty()) {
            compare(programs, probes, full.size());
        }
        return true;
    }

    private static boolean layered(Material material) {
        // TODO: Support authored depth comparisons/offsets and additive/lightning/alpha-replace far producers.
        return (material.transparency() == Transparency.TRANSLUCENT
                || material.transparency() == Transparency.ORDER_INDEPENDENT)
                && material.depthTest() == DepthTest.LEQUAL && material.writeMask().color()
                && !material.polygonOffset();
    }

    // Row boxes come from the same rest-pose GPU evaluation as brick selection. Retain neighbours whose real
    // geometry can supply a filter tap; they never enlarge the brick's reconstruction/ownership domain.
    private int[] layerRows(Brick brick) {
        Part part = brick.part;
        if (part.layerRowBounds == null) return part.rows;
        var rows = new IntArrayList();
        for (int row = 0; row < part.rows.length; row++) {
            boolean reaches = true;
            for (int axis = 0; axis < 3; axis++) {
                float lo = part.layerRowBounds[row * 6 + axis];
                float hi = part.layerRowBounds[row * 6 + 3 + axis];
                reaches &= (hi - brick.boxMin[axis]) * part.density >= -FarLayers.BAKE_HALO
                        && (lo - brick.boxMin[axis]) * part.density <= brick.extent[axis] + FarLayers.BAKE_HALO;
            }
            if (reaches) rows.add(part.rows[row]);
        }
        return rows.toIntArray();
    }

    private void captureLayers(Job job, FarPrograms programs) {
        Brick brick = job.brick;
        int[] captureRows = layerRows(brick);
        int sources = 0;
        int[] counts = new int[batches.size()];
        var rowBases = new int[captureRows.length];
        for (int r = 0; r < captureRows.length; r++) {
            var batch = batches.get(pageWords.getInt((captureRows[r] >>> 5) * 4));
            rowBases[r] = sources;
            for (int mesh = 0; mesh < batch.meshes.size(); mesh++) {
                if (layered(batch.materials.get(mesh))) sources = Math.addExact(sources,
                        batch.meshes.get(mesh).indexCount() / 3);
            }
            counts[batch.index] += 6;
        }
        var bake = layers.reserve(sources);
        brick.layerBake = bake;
        brick.layerScratch = FarLayers.scratch(brick.extent);
        brick.layerSummaryReady = false;
        pendingLayers.add(brick);
        int[] bases = new int[counts.length];
        int itemCount = 0;
        for (int i = 0; i < counts.length; i++) {
            bases[i] = itemCount;
            itemCount += counts[i];
        }
        int[] fill = bases.clone();
        int[] items = new int[itemCount * 3];
        for (int r = 0; r < captureRows.length; r++) {
            int handle = captureRows[r];
            int batch = pageWords.getInt((handle >>> 5) * 4);
            int start = fill[batch];
            for (int face = 0; face < 6; face++) {
                int at = (start + face) * 3;
                items[at] = objectUint(handle);
                items[at + 1] = face;
                items[at + 2] = rowBases[r];
            }
            fill[batch] += 6;
        }
        bake.indices.ensure(Math.max(items.length * 4L, 4));
        if (items.length != 0) bake.indices.write(0, items, items.length);
        bake.jobs.ensure(JOB_BYTES);
        bake.jobs.write(0, new int[]{brick.layerScratch[0], brick.layerScratch[1], brick.layerScratch[2], brick.slot,
                job.tile.slot, 0, 0, 0}, 8);
        writeDraws(bake.commands, counts, bases);
        int[] metadata = new int[Math.max(drawList.size(), 1) * 4];
        for (int i = 0; i < drawList.size(); i++) {
            Draw draw = drawList.get(i);
            int prefix = 0;
            for (int m = 0; m < draw.meshIndex; m++) {
                if (layered(draw.batch.materials.get(m))) prefix += draw.batch.meshes.get(m).indexCount() / 3;
            }
            metadata[i * 4] = prefix;
            metadata[i * 4 + 2] = draw.mesh.indexCount() / 3;
            metadata[i * 4 + 3] = draw.batch.model.meshes().get(draw.meshIndex).mesh().indexSequence()
                    == QuadIndexSequence.INSTANCE ? 1 : 0;
            metadata[i * 4 + 1] = (draw.material.backfaceCulling() ? 1 : 0)
                    | (draw.material.polygonOffset() ? 2 : 0)
                    | draw.material.depthTest().ordinal() << 2
                    | draw.material.transparency().ordinal() << 8
                    | (draw.material.writeMask().depth() ? 1 << 16 : 0)
                    | (draw.material.writeMask().color() ? 1 << 17 : 0);
        }
        bake.drawMeta.ensure(metadata.length * 4L);
        bake.drawMeta.write(0, metadata, metadata.length);
        GlStateTracker.invalidateEncoderProgramCache();
        try (var saved = FarGlState.capture()) {
            // The existing scratch framebuffer provides dimensions only; capture writes SSBOs, never its attachments.
            if (rawFramebuffer == 0) rawFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(rawFramebuffer, GL30C.GL_DEPTH_ATTACHMENT, 0, 0);
            GL45C.glNamedFramebufferTexture(rawFramebuffer, GL30C.GL_STENCIL_ATTACHMENT, 0, 0);
            GL45C.glNamedFramebufferTexture(rawFramebuffer, GL30C.GL_COLOR_ATTACHMENT0,
                    ((GlTexture) scratchColor).glId(), 0);
            for (int attachment = 1; attachment < 4; attachment++)
                GL45C.glNamedFramebufferTexture(rawFramebuffer, GL30C.GL_COLOR_ATTACHMENT0 + attachment, 0, 0);
            GL45C.glNamedFramebufferDrawBuffer(rawFramebuffer, GL30C.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, rawFramebuffer);
            GlStateManager._viewport(0, 0, SCRATCH_SIZE, SCRATCH_SIZE);
            GL11C.glDisable(GL32C.GL_DEPTH_CLAMP);
            for (int i = 0; i < 4; i++) GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + i);
            vertexSlices[0] = meshPool.vertexBuffer().slice();
            objects.bindStorage(SSBO_OBJECTS);
            parts.bindStorage(SSBO_PARTS);
            noLight.bindStorage(SSBO_LIGHT_LUT);
            noLight.bindStorage(SSBO_LIGHT_SECTIONS);
            tiles.bindStorage(SSBO_TILES);
            far.bindUniform(UBO_FAR);
            bake.indices.bindStorage(SSBO_INDICES);
            bake.jobs.bindStorage(SSBO_JOBS);
            bake.commands.bindStorage(SSBO_DRAWS);
            bake.drawMeta.bindStorage(11);
            bake.bind();
            if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) GlBindlessTable.bind();
            GlBufferType.DRAW_INDIRECT_BUFFER.bind(bake.commands.handle());
            for (Run run : runs) {
                if (!layered(run.material)) continue;
                var pipeline = rawPipeline(run.capture);
                pipeline.vertices(vertexSlices);
                pipeline.indices(meshPool.indexBuffer());
                int program = pipeline.program();
                GL41C.glProgramUniform1ui(program,
                        pipeline.uniformLocation("_flw_farLayerNodeCapacity"), bake.capacity);
                GL41C.glProgramUniform1f(program,
                        pipeline.uniformLocation("_flw_farLayerDensity"), brick.part.density);
                GL41C.glProgramUniform3f(program,
                        pipeline.uniformLocation("_flw_farLayerExtent"),
                        brick.extent[0], brick.extent[1], brick.extent[2]);
                if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                    submit(run.start, run.end);
                } else {
                    for (int d = run.start; d < run.end; d++) {
                        var material = drawList.get(d).material;
                        pipeline.texture("Sampler0", Minecraft.getInstance().getTextureManager()
                                                              .getTexture(material.texture()).getTextureView(),
                                MaterialSamplers.get(material));
                        submit(d, d + 1);
                    }
                }
            }
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            layers.finish(bake, programs, completed -> {
                if (!brick.part.removed && brick.layerBake == completed) brick.layerSummaryReady = true;
            });
        }
    }

    private void finishLayers(FarPrograms programs) {
        if (pendingLayers.isEmpty()) return;
        try (var state = FarComputeState.captureLayerPublication()) {
            finishLayerJobs(programs);
        }
    }

    private void finishLayerJobs(FarPrograms programs) {
        for (var brick : List.copyOf(pendingLayers)) {
            if (!brick.layerSummaryReady) continue;
            var bake = brick.layerBake;
            if (bake.overflow) {
                layers.release(bake);
                brick.layerBake = null;
                pendingLayers.remove(brick);
                subdivideLayeredBrick(brick);
                continue;
            }
            if (!bake.comparisonRequested) {
                layers.compare(bake, layerAppearance(brick),
                        brick.extent, brick.layerScratch, programs);
            }
            if (!bake.comparisonReady) continue;
            var tile = layers.publish(bake, layerAppearance(brick),
                    brick.extent, brick.clipExtent, brick.layerScratch, programs);
            if (tile == null) throw new IllegalStateException("Far layered atlas byte capacity exhausted");
            brick.layerTile = tile;
            brick.layerBake = null;
            pendingLayers.remove(brick);
            writeInt(parts, (long) brick.slot * PART_BYTES + 76, tile.slot());
            if (brick.opaqueReady) baked(brick);
        }
    }

    private void subdivideLayeredBrick(Brick brick) {
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            if (brick.extent[a] >= FarAtlas.ALIGN * 2 && brick.clipExtent[a] > FarAtlas.ALIGN
                    && (axis < 0 || brick.clipExtent[a] > brick.clipExtent[axis])) axis = a;
        }
        if (axis < 0) throw new IllegalStateException("Far layered minimum brick exceeds raw sample capacity");
        int old = brick.extent[axis];
        int interiorCut = ((int) Math.ceil(brick.clipExtent[axis] / FarAtlas.ALIGN) - 1) * FarAtlas.ALIGN;
        int first = Math.min(old / (FarAtlas.ALIGN * 2) * FarAtlas.ALIGN, interiorCut);
        int second = old - first;
        brick.revision++;
        brick.opaqueReady = false;
        brick.probed = false;
        if (brick.tile != null) {
            release(brick.tile);
            brick.tile = null;
        }
        var next = new Brick(freePartSlots.isEmpty() ? partSlots++ : freePartSlots.popInt(), brick.part);
        next.bakeRows = brick.bakeRows;
        next.ownedMax = brick.ownedMax;
        brick.ownedMax &= ~(1 << axis);
        System.arraycopy(brick.boxMin, 0, next.boxMin, 0, 3);
        System.arraycopy(brick.extent, 0, next.extent, 0, 3);
        System.arraycopy(brick.clipExtent, 0, next.clipExtent, 0, 3);
        next.clipExtent[axis] -= first;
        brick.clipExtent[axis] = first;
        brick.extent[axis] = first;
        next.extent[axis] = second;
        next.boxMin[axis] += first / brick.part.density;
        brick.key = new Appearance(brick.part.batchIndices, brick.extent, brick.part.density, 0, SINGLE_GRID);
        next.key = new Appearance(next.part.batchIndices, next.extent, next.part.density, 0, SINGLE_GRID);
        parts.ensure((long) partSlots * PART_BYTES);
        GL45C.glCopyNamedBufferSubData(parts.handle(), parts.handle(), (long) brick.slot * PART_BYTES,
                (long) next.slot * PART_BYTES, PART_BYTES);
        for (var child : List.of(brick, next)) {
            writeInts(parts, (long) child.slot * PART_BYTES + 64,
                    Float.floatToRawIntBits(child.boxMin[0]), Float.floatToRawIntBits(child.boxMin[1]),
                    Float.floatToRawIntBits(child.boxMin[2]), -1);
            writeInt(parts, (long) child.slot * PART_BYTES + 60,
                    PART_LIVE | child.ownedMax << PART_OWNED_MAX_SHIFT);
            bakeQueue.addLast(child);
        }
        brick.part.others = Arrays.copyOf(brick.part.others, brick.part.others.length + 1);
        brick.part.others[brick.part.others.length - 1] = next;
        brick.part.unbaked++;
    }

    private void bindScratch() {
        opaqueBounds.bindStorage(SSBO_INPUT);
        parts.bindStorage(SSBO_PARTS);
        GL45C.glBindTextureUnit(UNIT_SCRATCH_DEPTH, ((GlTexture) scratchDepth).glId());
        GL42C.glBindImageTexture(0, ((GlTexture) scratchColor).glId(), 0, false, 0, GL15C.GL_READ_ONLY,
                GL11C.GL_RGBA8);
        GL42C.glBindImageTexture(3, ((GlTexture) scratchNormal).glId(), 0, false, 0, GL15C.GL_READ_ONLY,
                GL11C.GL_RGBA8);
        GL42C.glBindImageTexture(5, ((GlTexture) scratchMaterial).glId(), 0, false, 0, GL15C.GL_READ_ONLY,
                GL30C.GL_RGBA32UI);
    }

    // An impostor at fewer texels per block than a block's pixels would magnify: the entry stays mesh until then.
    private void coarsen(Entry entry, float density) {
        if (density < entry.coarsest) {
            entry.coarsest = density;
            writeInt(entries, (long) entry.slot * ENTRY_BYTES + 28,
                    Float.floatToRawIntBits(Math.min(entry.meshSwitch, density)));
        }
    }

    private void baked(Brick brick) {
        brick.opaqueReady = true;
        if (brick.part.layered && brick.layerTile == null) return;
        writeInt(parts, (long) brick.slot * PART_BYTES + 60,
                PART_LIVE | PART_BAKED | brick.ownedMax << PART_OWNED_MAX_SHIFT);
        brick.baked = true;
        bakedBricks++;
        Part part = brick.part;
        if (--part.unbaked == 0) {
            bakedParts++;
            if (--part.entry.unbaked == 0) {
                writeInt(entries, (long) part.entry.slot * ENTRY_BYTES + 44, 1);
            }
        }
    }

    // A removed part's brick: its baked count, tile and record.
    private void drop(Brick brick) {
        brick.revision++;
        pendingLayers.remove(brick);
        if (brick.layerBake != null) {
            layers.release(brick.layerBake);
            brick.layerBake = null;
        }
        if (brick.layerTile != null) {
            layers.release(brick.layerTile);
            brick.layerTile = null;
        }
        if (brick.baked) {
            bakedBricks--;
        }
        if (brick.tile != null) {
            release(brick.tile);
            brick.tile = null;
        }
        writeInt(parts, (long) brick.slot * PART_BYTES + 60, 0);
    }

    // The scratch views of this round's rows.
    private void bakeRows() {
        if (scratchColor == null) {
            var device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_DST;
            scratchColor = device.createTexture("flywheel:far_bake_color", usage, GpuFormat.RGBA8_UNORM, SCRATCH_SIZE,
                    SCRATCH_SIZE, 1, 1);
            scratchDepth = device.createTexture("flywheel:far_bake_depth", usage, GpuFormat.D32_FLOAT, SCRATCH_SIZE,
                    SCRATCH_SIZE, 1, 1);
            scratchNormal = device.createTexture("flywheel:far_bake_normal", usage, GpuFormat.RGBA8_UNORM,
                    SCRATCH_SIZE, SCRATCH_SIZE, 1, 1);
            scratchMaterial = device.createTexture("flywheel:far_bake_material", usage, GpuFormat.RGBA32_UINT,
                    SCRATCH_SIZE, SCRATCH_SIZE, 1, 1);
            scratchColorView = device.createTextureView(scratchColor);
            scratchDepthView = device.createTextureView(scratchDepth);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(scratchColor, new Vector4f(0),
                scratchDepth, 0.0);
        if (drawList.isEmpty() || meshPool.vertexBuffer() == null || meshPool.indexBuffer() == null) {
            return;
        }
        GlStateTracker.invalidateEncoderProgramCache();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "flywheel:far_bake", scratchColorView, Optional.empty(), scratchDepthView,
                OptionalDouble.empty())) {
            beginRows(pass, new Matrix4f());
            opaqueBounds.bindStorage(SSBO_INPUT);
            bakeIndices.bindStorage(SSBO_INDICES);
            bakeDraws.bindStorage(SSBO_DRAWS);
            jobs.bindStorage(SSBO_JOBS);
            tiles.bindStorage(SSBO_TILES);
            // A brick's face rects clip its part's rows (gl_ClipDistance 0-3); rows in front of a face clamp onto it
            // (depth 1), capping what the brick cuts, and those behind to 0, uncovered. State Blaze3D does not track.
            int[] capabilities = {GL30C.GL_CLIP_DISTANCE0, GL30C.GL_CLIP_DISTANCE0 + 1, GL30C.GL_CLIP_DISTANCE0 + 2,
                    GL30C.GL_CLIP_DISTANCE0 + 3, GL32C.GL_DEPTH_CLAMP};
            boolean[] enabled = new boolean[capabilities.length];
            for (int i = 0; i < capabilities.length; i++) {
                enabled[i] = GL11C.glIsEnabled(capabilities[i]);
                GL11C.glEnable(capabilities[i]);
            }
            drawRows(pass, bakeDraws, true);
            for (int i = 0; i < capabilities.length; i++) {
                if (!enabled[i]) {
                    GL11C.glDisable(capabilities[i]);
                }
            }
        }
    }

    // Each probe's scratch view against its candidates, read back: the first within tolerance becomes its tile.
    private void compare(FarPrograms programs, List<Job> probes, int firstJob) {
        IntArrayList pairs = new IntArrayList();
        for (int i = 0; i < probes.size(); i++) {
            Job job = probes.get(i);
            job.firstCompare = pairs.size() / 4;
            for (Tile candidate : job.candidates) {
                pairs.add(firstJob + i);
                pairs.add(candidate.slot);
                pairs.add(0);
                pairs.add(0);
            }
        }
        int count = pairs.size() / 4;
        compares.ensure(pairs.size() * 4L);
        compares.write(0, pairs.elements(), pairs.size());
        results.ensure(count * 4L);
        results.write(0, new int[count], count);
        programs.bakeCompare().bind();
        jobs.bindStorage(SSBO_JOBS);
        tiles.bindStorage(SSBO_TILES);
        results.bindStorage(SSBO_RESULTS);
        compares.bindStorage(SSBO_COMPARES);
        bindScratch();
        FarAtlas.bindImage(1, atlas.color(), 0, GL15C.GL_READ_ONLY, false);
        FarAtlas.bindImage(2, atlas.aux(), 0, GL15C.GL_READ_ONLY, true);
        FarAtlas.bindImage(4, atlas.normal(), 0, GL15C.GL_READ_ONLY, false);
        atlas.bindMaterialImage(6, 0, GL15C.GL_READ_ONLY);
        glDispatchCompute(count * 6, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        readback.request(results.handle(), 0, count * 4, data -> {
            for (Job job : probes) {
                Tile match = null;
                for (int c = 0; c < job.candidates.size() && match == null; c++) {
                    if (data.getInt((job.firstCompare + c) * 4) <= job.tile.tolerance()) {
                        match = job.candidates.get(c);
                    }
                }
                Brick brick = job.brick;
                if (!brick.part.removed && brick.revision == job.revision && match != null) {
                    match.refs++;
                    brick.tile = match;
                    writeInt(parts, (long) brick.slot * PART_BYTES + 52, match.slot);
                    List<Tile> known = appearances.get(brick.key);
                    known.remove(match);
                    known.addFirst(match);
                    baked(brick);
                } else if (!brick.part.removed && brick.revision == job.revision) {
                    brick.probed = true;
                    bakeQueue.addFirst(brick);
                }
                job.candidates.forEach(this::release);
            }
        });
    }

    // A new tile of the brick's appearance for its bake box, or null once the atlas is full (its part stays at mesh
    // level).
    private @Nullable Tile tile(Brick brick) {
        FarAtlas.Rect[] rects = new FarAtlas.Rect[3];
        for (int a = 0; a < 3; a++) {
            rects[a] = atlas.allocate(2 * brick.extent[(a + 1) % 3], brick.extent[(a + 2) % 3]);
            if (rects[a] == null) {
                for (int i = 0; i < a; i++) {
                    atlas.free(rects[i]);
                }
                return null;
            }
        }
        Tile tile = new Tile(freeTileSlots.isEmpty() ? tileSlots++ : freeTileSlots.popInt(), rects, brick.key);
        brick.tile = tile;
        appearances.computeIfAbsent(brick.key, _ -> new ArrayList<>()).add(tile);
        int one = Float.floatToRawIntBits(1);
        tiles.ensure((long) tileSlots * TILE_BYTES);
        writeInts(tiles, (long) tile.slot * TILE_BYTES, rects[0].packed(), rects[1].packed(), rects[2].packed(),
                Float.floatToRawIntBits(brick.part.density), brick.extent[0], brick.extent[1], brick.extent[2], 0,
                one, one, one, one, one, one, 0, 0);
        return tile;
    }

    private void release(Tile tile) {
        if (--tile.refs > 0) {
            return;
        }
        List<Tile> known = appearances.get(tile.appearance);
        known.remove(tile);
        if (known.isEmpty()) {
            appearances.remove(tile.appearance);
        }
        for (FarAtlas.Rect rect : tile.rects) {
            atlas.free(rect);
        }
        freeTileSlots.push(tile.slot);
    }

    private FarGlDraw rawPipeline(RenderPipeline pipeline) {
        var draw = rawPipelines.get(pipeline);
        draw.bind();
        draw.uniform("Projection", projectionSlice);
        draw.uniform("Fog", fogSlice);
        draw.uniform("Lighting", lightingSlice);
        draw.uniform("DynamicTransforms", transformSlice);
        draw.uniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        var mc = Minecraft.getInstance();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        draw.texture("Sampler1", mc.gameRenderer.overlayTexture().getTextureView(), sampler);
        draw.texture("Sampler2", mc.gameRenderer.lightmap(), sampler);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GlStateManager._disableScissorTest();
        return draw;
    }

    private void drawRaw(int color, int depth) {
        if (rawFramebuffer == 0) rawFramebuffer = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(rawFramebuffer, GL30C.GL_COLOR_ATTACHMENT0, color, 0);
        GL45C.glNamedFramebufferTexture(rawFramebuffer, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
        GL45C.glNamedFramebufferDrawBuffer(rawFramebuffer, GL30C.GL_COLOR_ATTACHMENT0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, rawFramebuffer);
        GL11C.glViewport(0, 0, drawWidth, drawHeight);
        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(0, 15);
        GlStateManager._depthMask(true);
        GL45C.glClearNamedFramebufferfv(rawFramebuffer, GL11C.GL_COLOR, 0, new float[]{0, 0, 0, 0});
        GL45C.glClearNamedFramebufferfv(rawFramebuffer, GL11C.GL_DEPTH, 0, new float[]{0});
        if (computeSnapshot != null && !drawList.isEmpty()
                && meshPool.vertexBuffer() != null && meshPool.indexBuffer() != null) {
            vertexSlices[0] = meshPool.vertexBuffer().slice();
            objects.bindStorage(SSBO_OBJECTS);
            parts.bindStorage(SSBO_PARTS);
            noLight.bindStorage(SSBO_LIGHT_LUT);
            noLight.bindStorage(SSBO_LIGHT_SECTIONS);
            far.bindUniform(UBO_FAR);
            indices.bindStorage(SSBO_INDICES);
            draws.bindStorage(SSBO_DRAWS);
            if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) GlBindlessTable.bind();
            GlBufferType.DRAW_INDIRECT_BUFFER.bind(draws.handle());
            for (Run run : runs) {
                if (layered(run.material) || run.pipelines == null) continue;
                var pipeline = rawPipeline(run.pipelines[0]);
                pipeline.vertices(vertexSlices);
                pipeline.indices(meshPool.indexBuffer());
                if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                    submitCoveredRows(run.start, run.end, run);
                } else {
                    int start = run.start;
                    for (int i = run.start; i < run.end; i++) {
                        var material = drawList.get(i).material;
                        if (i + 1 < run.end && !textureDiffers(material, drawList.get(i + 1).material)) continue;
                        pipeline.texture("Sampler0", Minecraft.getInstance().getTextureManager()
                                                              .getTexture(material.texture()).getTextureView(),
                                MaterialSamplers.get(material));
                        submitCoveredRows(start, i + 1, run);
                        start = i + 1;
                    }
                }
            }
        }
        if (tileSlots == 0 || impostorPipeline == null || computeSnapshot == null) {
            return;
        }
        {
            var pipeline = rawPipeline(impostorPipeline);
            vertexSlices[0] = null;
            pipeline.vertices(vertexSlices);
            parts.bindStorage(SSBO_PARTS);
            impostors.bindStorage(SSBO_IMPOSTORS);
            tiles.bindStorage(SSBO_TILES);
            far.bindUniform(UBO_FAR);
            GL45C.glBindTextureUnit(UNIT_ATLAS_COLOR, atlas.color());
            GL45C.glBindTextureUnit(UNIT_ATLAS_AUX, atlas.aux());
            GL45C.glBindTextureUnit(UNIT_ATLAS_NORMAL, atlas.normal());
            if (surfaceNormal != 0) {
                GL45C.glBindTextureUnit(UNIT_ATLAS_MATERIAL, atlas.material());
            }
            GlBufferType.DRAW_INDIRECT_BUFFER.bind(impostorCommand.handle());
            if (surfaceNormal == 0) {
                GL40C.glDrawArraysIndirect(GL_TRIANGLES, 0);
            } else {
                try (var _ = FarMrt.attach(rawFramebuffer, surfaceNormal, surfaceLight, surfaceMaterial)) {
                    GL40C.glDrawArraysIndirect(GL_TRIANGLES, 0);
                }
            }
        }
    }

    // The state every row draw shares: transforms, mesh buffers, overlay and lightmap, render origin, rows, parts, and
    // an empty light storage in place of the main engine's.
    private void beginRows(RenderPass pass, Matrix4fc modelView) {
        Minecraft mc = Minecraft.getInstance();
        GpuSampler lightOverlaySampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(
                new Matrix4f(modelView)));
        pass.setVertexBuffer(0, meshPool.vertexBuffer().slice());
        meshPool.bindExtras(pass);
        pass.setIndexBuffer(meshPool.indexBuffer(), IndexType.INT);
        pass.setUniform("Sampler1", mc.gameRenderer.overlayTexture().getTextureView(), lightOverlaySampler);
        pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), lightOverlaySampler);
        pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        objects.bindStorage(SSBO_OBJECTS);
        parts.bindStorage(SSBO_PARTS);
        noLight.bindStorage(SSBO_LIGHT_LUT);
        noLight.bindStorage(SSBO_LIGHT_SECTIONS);
        far.bindUniform(UBO_FAR);
    }

    private void drawRows(RenderPass pass, FarBuffer commands, boolean bake) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        boolean bindless = GlCompat.SUPPORTS_BINDLESS_TEXTURES;
        if (bindless) {
            GlBindlessTable.refresh(textureManager);
            GlBindlessTable.bind();
        }
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(commands.handle());
        RenderPipeline lastPipeline = null;
        Identifier lastTexture = null;
        GpuSampler lastSampler = null;
        for (Run run : runs) {
            if (layered(run.material)) continue;
            RenderPipeline pipeline = run.pipelines[bake ? 1 : 0];
            boolean prime = pipeline != lastPipeline;
            if (prime) {
                lastPipeline = pipeline;
                pass.setRenderPipeline(pipeline);
            }
            if (bindless) {
                if (prime) {
                    pass.drawIndexed(0, 0, 0, 0, 0);
                }
                submitRows(run.start, run.end, bake);
                continue;
            }
            int start = run.start;
            for (int i = run.start; i < run.end; i++) {
                Material material = drawList.get(i).material;
                if (i + 1 < run.end && !textureDiffers(material, drawList.get(i + 1).material)) {
                    continue;
                }
                GpuSampler sampler = MaterialSamplers.get(material);
                if (!material.texture().equals(lastTexture) || sampler != lastSampler) {
                    lastTexture = material.texture();
                    lastSampler = sampler;
                    pass.setUniform("Sampler0", textureManager.getTexture(lastTexture).getTextureView(), sampler);
                    prime = true;
                }
                if (prime) {
                    pass.drawIndexed(0, 0, 0, 0, 0);
                    prime = false;
                }
                submitRows(start, i + 1, bake);
                start = i + 1;
            }
        }
    }

    private void submit(int start, int end) {
        bindUniform(UBO_INSTANCE_DRAW, renderPassUniforms.material(0));
        bindUniform(UBO_EMBED_DRAW, renderPassUniforms.embedDraw(start));
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, (long) start * DRAW_BYTES, end - start,
                DRAW_BYTES);
    }

    private void submitCoveredRows(int start, int end, Run run) {
        if (run.coverage.covers(run.required)) {
            submitRows(start, end, false);
            return;
        }
        int first = -1;
        for (int i = start; i < end; i++) {
            if (run.coverage.covers(drawList.get(i).required)) {
                if (first < 0) first = i;
            } else if (first >= 0) {
                submitRows(first, i, false);
                first = -1;
            }
        }
        if (first >= 0) submitRows(first, end, false);
    }

    private void submitRows(int start, int end, boolean bake) {
        int normal = bake ? ((GlTexture) scratchNormal).glId() : surfaceNormal;
        if (normal == 0) {
            submit(start, end);
        } else {
            try (var _ = FarMrt.attach(normal, bake ? 0 : surfaceLight,
                    bake ? ((GlTexture) scratchMaterial).glId() : surfaceMaterial)) {
                submit(start, end);
            }
        }
    }

    private void writeFar(int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(FAR_BYTES);
            buffer.putInt(tick).putFloat(partialTick).putFloat(meshScale).putInt(count)
                  .putFloat(camera.x).putFloat(camera.y).putFloat(camera.z).putFloat(camera.w);
            occlusionClip.get(buffer.position(), buffer);
            buffer.position(96);
            buffer.putFloat(occlusionWidth).putFloat(occlusionHeight).putFloat(occlusionLevels)
                  .putFloat(depthCulling ? 1 : 0)
                  .putInt(partSlots).putInt(0).putInt(0).putInt(0);
            far.write(0, MemoryUtil.memAddress0(buffer), FAR_BYTES);
        }
        far.bindUniform(UBO_FAR);
    }

    private void uploadModels() {
        models.ensure((long) batches.size() * MODEL_BYTES);
        long size = (long) batches.size() * MODEL_BYTES;
        long ptr = MemoryUtil.nmemAlloc(size);
        try {
            for (Batch batch : batches) {
                long at = ptr + (long) batch.index * MODEL_BYTES;
                MemoryUtil.memPutInt(at, 0);
                MemoryUtil.memPutInt(at + 4, batch.baseInstance);
                MemoryUtil.memPutInt(at + 8, 0);
                MemoryUtil.memPutFloat(at + 12, batch.sphere.x());
                MemoryUtil.memPutFloat(at + 16, batch.sphere.y());
                MemoryUtil.memPutFloat(at + 20, batch.sphere.z());
                MemoryUtil.memPutFloat(at + 24, batch.sphere.w());
            }
            models.write(0, ptr, size);
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private void uploadModelBounds() {
        int[] words = new int[batches.size() * 8];
        for (Batch batch : batches) {
            for (int a = 0; a < 3; a++) {
                words[batch.index * 8 + a] = Float.floatToRawIntBits(batch.boxMin[a]);
                words[batch.index * 8 + 4 + a] = Float.floatToRawIntBits(batch.boxMax[a]);
            }
        }
        modelBounds.ensure(words.length * 4L);
        modelBounds.write(0, words, words.length);
    }

    private void rebuildDraws() {
        int base = 0;
        drawList.clear();
        for (Batch batch : batches) {
            batch.baseInstance = base;
            base += batch.pages * PAGE_ROWS;
            for (int i = 0; i < batch.meshes.size(); i++) {
                var material = batch.materials.get(i);
                if (material.transparency() == Transparency.OPAQUE || layered(material))
                    drawList.add(new Draw(batch, i, material, batch.meshes.get(i)));
            }
        }
        indices.ensure(Math.max(base, 1) * 4L);
        drawList.sort(DRAW_ORDER);
        runs.clear();
        required = ProgramGeneration.NONE;
        var runRequired = ProgramGeneration.NONE;
        for (int i = 0, start = 0; i < drawList.size(); i++) {
            var draw = drawList.get(i);
            runRequired = runRequired.max(draw.required);
            required = required.max(draw.required);
            if (i + 1 == drawList.size()
                    || !MaterialRenderState.uberPipelineEquals(drawList.get(i).material,
                    drawList.get(i + 1).material)) {
                runs.add(new Run(draw.material, start, i + 1, runRequired));
                start = i + 1;
                runRequired = ProgramGeneration.NONE;
            }
        }
        int[] bases = new int[batches.size()];
        for (Batch batch : batches) {
            bases[batch.index] = batch.baseInstance;
        }
        writeDraws(draws, new int[batches.size()], bases);
    }

    // Every draw command, instanceCount and baseInstance per batch.
    private void writeDraws(FarBuffer buffer, int[] counts, int[] bases) {
        if (drawList.isEmpty()) {
            return;
        }
        long size = (long) drawList.size() * DRAW_BYTES;
        buffer.ensure(size);
        long ptr = MemoryUtil.nmemAlloc(size);
        try {
            for (int i = 0; i < drawList.size(); i++) {
                Draw draw = drawList.get(i);
                long at = ptr + (long) i * DRAW_BYTES;
                MemoryUtil.memPutInt(at, draw.mesh.indexCount());
                MemoryUtil.memPutInt(at + 4, counts[draw.batch.index]);
                MemoryUtil.memPutInt(at + 8, draw.mesh.firstIndex());
                MemoryUtil.memPutInt(at + 12, draw.mesh.baseVertex());
                MemoryUtil.memPutInt(at + 16, bases[draw.batch.index]);
                MemoryUtil.memPutInt(at + 20, draw.batch.index);
                MemoryUtil.memPutInt(at + 24, FarMaterials.resolve(draw.batch.model, draw.material,
                        draw.batch.model.meshes().get(draw.meshIndex).mesh(), draw.batch.fallbackState));
                MemoryUtil.memPutInt(at + 28, MaterialEncoder.packUberShader(draw.material));
                MemoryUtil.memPutInt(at + 32, MaterialEncoder.packProperties(draw.material));
                MemoryUtil.memPutInt(at + 36, draw.mesh.vertexCount());
                MemoryUtil.memPutInt(at + 40, draw.mesh.meshletBase());
                MemoryUtil.memPutInt(at + 44, BindlessSlots.slot(draw.material) | draw.batch.typeId << 16);
            }
            buffer.write(0, ptr, size);
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private Batch batch(FarInstance snapshot) {
        return batchByKey.computeIfAbsent(new BatchKey(snapshot.type(), snapshot.model(), snapshot.bias(),
                snapshot.fallbackState()), key -> {
            Batch batch = new Batch(batches.size(), key, meshPool);
            batches.add(batch);
            drawsDirty = true;
            return batch;
        });
    }

    private int objectUint(int handle) {
        int page = handle >>> 5;
        return pageWords.getInt(page * 4 + 2) + (handle & 31) * (pageWords.getInt(page * 4 + 3) >>> 16);
    }

    private int allocate(Batch batch) {
        while (!batch.open.isEmpty()) {
            int page = batch.open.topInt();
            int valid = pageWords.getInt(page * 4 + 1);
            if (valid != -1) {
                int lane = Integer.numberOfTrailingZeros(~valid);
                pageWords.set(page * 4 + 1, valid | 1 << lane);
                pagesDirty = true;
                return page << 5 | lane;
            }
            batch.open.popInt();
        }
        // TODO: return empty pages to a pool shared by batches of the same stride.
        int page = pageWords.size() / 4;
        pageWords.add(batch.index);
        pageWords.add(1);
        pageWords.add(objectUints + RECORD_UINTS);
        pageWords.add(batch.typeId | batch.strideUints << 16);
        objectUints += PAGE_ROWS * batch.strideUints;
        objects.ensure((long) objectUints * 4);
        batch.pages++;
        batch.open.push(page);
        pagesDirty = true;
        drawsDirty = true;
        return page << 5;
    }

    private static void writeInt(FarBuffer buffer, long offset, int value) {
        writeInts(buffer, offset, value);
    }

    private static void writeInts(FarBuffer buffer, long offset, int... values) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45C.nglNamedBufferSubData(buffer.handle(), offset, values.length * 4L,
                    MemoryUtil.memAddress(stack.ints(values)));
        }
    }

    private static void bindUniform(int binding, GpuBufferSlice slice) {
        glBindBufferRange(GL_UNIFORM_BUFFER, binding, ((GlBuffer) slice.buffer()).handle(), slice.offset(),
                slice.length());
    }

    private static boolean textureDiffers(Material a, Material b) {
        return !a.texture().equals(b.texture()) || a.blur() != b.blur() || a.mipmap() != b.mipmap()
                || a.useTextureSampler() != b.useTextureSampler();
    }

    private static int sortable(float f) {
        int bits = Float.floatToRawIntBits(f);
        return bits < 0 ? ~bits : bits | 0x80000000;
    }

    private static float unsortable(int bits) {
        return Float.intBitsToFloat(bits < 0 ? bits & 0x7FFFFFFF : ~bits);
    }

    private record BatchKey(InstanceType<?> type, Model model, int bias, BlockState fallbackState) {
    }

    private record Draw(Batch batch, int meshIndex, Material material, MeshPool.PooledMesh mesh,
                        ProgramGeneration required) {
        private Draw(Batch batch, int meshIndex, Material material, MeshPool.PooledMesh mesh) {
            this(batch, meshIndex, material, mesh, required(batch, material));
        }

        private static ProgramGeneration required(Batch batch, Material material) {
            int shaders = MaterialEncoder.packUberShader(material);
            return ProgramGeneration.required(batch.typeId, shaders & 0xFFFF, shaders >>> 16);
        }
    }

    private static final class Run {
        final Material material;
        final int start;
        final int end;
        final ProgramGeneration required;
        RenderPipeline @Nullable [] pipelines;
        private @Nullable RenderPipeline capture;
        ProgramGeneration coverage = ProgramGeneration.NONE;

        private Run(Material material, int start, int end, ProgramGeneration required) {
            this.material = material;
            this.start = start;
            this.end = end;
            this.required = required;
        }
    }

    // Rows sharing a pose form one part.
    private record Pose(float pivotX, float pivotY, float pivotZ, float axisX, float axisY, float axisZ,
                        float radiansPerTick, int startTick, float phase, Matrix4fc frame, Matrix3fc normal,
                        boolean terrain) {
        private static Pose of(FarRow row) {
            return new Pose(row.pivot().x(), row.pivot().y(), row.pivot().z(), row.axis().x(), row.axis().y(),
                    row.axis().z(), row.radiansPerTick(), row.startTick(), row.phase(), row.snapshot().pose(),
                    row.snapshot().normal(), row.snapshot().terrain());
        }
    }

    /**
     * What a tile's look follows from: the batches of its part's rows, its bake box size, and which brick of its part's
     * grid it is (bricks per axis, 8 bits each).
     */
    private record Appearance(List<Integer> batches, List<Integer> extent, float density, int brick, int grid) {
        private Appearance(int[] batches, int[] extent, float density, int brick, int grid) {
            this(Arrays.stream(batches).boxed().toList(), Arrays.stream(extent).boxed().toList(), density, brick,
                    grid);
        }
    }

    private static final class Entry {
        private final int slot;
        private final Part[] parts;
        private final long pos;
        private final Vec3i origin;
        private final boolean meshOnly;
        private int unbaked;
        // Most px per block its rows switch at, and the fewest texels per block its parts baked at.
        private float meshSwitch;
        private float coarsest = Float.POSITIVE_INFINITY;

        private Entry(int slot, int parts, long pos, Vec3i origin, boolean meshOnly) {
            this.slot = slot;
            this.parts = new Part[parts];
            this.pos = pos;
            this.origin = origin;
            this.meshOnly = meshOnly;
            unbaked = parts;
        }
    }

    // One impostor box: rows seen from the 6 faces of its box into an atlas tile.
    private static LayerAppearance layerAppearance(Brick brick) {
        return new LayerAppearance(brick.key, brick.ownedMax, Float.floatToRawIntBits(brick.clipExtent[0]),
                Float.floatToRawIntBits(brick.clipExtent[1]), Float.floatToRawIntBits(brick.clipExtent[2]));
    }

    private record LayerAppearance(Appearance appearance, int ownedMax, int clipX, int clipY, int clipZ) {
    }

    private static class Brick {
        final int slot;
        Part part;
        final float[] boxMin = new float[3];
        final int[] extent = new int[3];
        final float[] clipExtent = new float[3];
        // The rows its bake draws.
        int[] bakeRows;
        @Nullable Appearance key;
        @Nullable Tile tile;
        // Its probe matched none of its appearance's tiles.
        boolean probed;
        boolean baked;
        boolean opaqueReady;
        int revision;
        int ownedMax = 7;
        FarLayers.@Nullable Bake layerBake;
        FarLayers.@Nullable Tile layerTile;
        boolean layerSummaryReady;
        int[] layerScratch;

        private Brick(int slot, Part part) {
            this.slot = slot;
            this.part = part;
        }

        // Its box from rest-pose bounds at density texels per block, extents in whole atlas units.
        void fit(float[] lo, float[] hi, float density, int brick, int grid) {
            for (int a = 0; a < 3; a++) {
                boxMin[a] = lo[a];
                extent[a] = extent(hi[a] - lo[a], density);
                clipExtent[a] = extent[a];
            }
            key = new Appearance(part.batchIndices, extent, density, brick, grid);
        }
    }

    // An entry's rows sharing one pose; itself its first brick.
    private static final class Part extends Brick {
        private static final Brick[] NO_BRICKS = {};

        private final Entry entry;
        private final int[] rows;
        // Batch index per row, sorted.
        private final int[] batchIndices;
        private final float meshSwitch;
        private final boolean spins;
        private final boolean meshOnly;
        private float density;
        // Its bricks but itself.
        private Brick[] others = NO_BRICKS;
        // Bricks not baked yet; -1 until they are known.
        private int unbaked = -1;
        private boolean removed;
        private boolean layered;
        private final Vector3f pivot = new Vector3f();
        private final Vector3f axis = new Vector3f();
        private float radiansPerTick;
        private float phase;
        private int startTick;
        private final float[] boundsLo = new float[3];
        private final float[] boundsHi = new float[3];
        private boolean hasBounds;
        private float @Nullable [] layerRowBounds;

        private Part(int slot, Entry entry, int[] rows, int[] batchIndices, float meshSwitch, boolean spins,
                     boolean meshOnly) {
            super(slot, null);
            part = this;
            bakeRows = rows;
            this.entry = entry;
            this.rows = rows;
            this.batchIndices = batchIndices;
            this.meshSwitch = meshSwitch;
            this.spins = spins;
            this.meshOnly = meshOnly;
        }

        // Texels per block from the mesh switch: dense models bake finer.
        private int fine() {
            int fine = TEXELS_PER_BLOCK;
            while (fine < MAX_TEXELS_PER_BLOCK && fine < meshSwitch) {
                fine *= 2;
            }
            return fine;
        }
    }

    // A part past BRICK_BLOCKS: its rest-pose bounds minimum, brick size and bricks per axis.
    private record Grid(Part part, float[] origin, float[] size, int[] count) {
        private int bricks() {
            return count[0] * count[1] * count[2];
        }

        private int code() {
            return count[0] | count[1] << 8 | count[2] << 16;
        }
    }

    private static final class Tile {
        private final int slot;
        private final FarAtlas.Rect[] rects;
        private final Appearance appearance;
        private int refs = 1;

        private Tile(int slot, FarAtlas.Rect[] rects, Appearance appearance) {
            this.slot = slot;
            this.rects = rects;
            this.appearance = appearance;
        }

        // Mismatched texels a tile of the same appearance may have: 1 in 64.
        private int tolerance() {
            List<Integer> e = appearance.extent;
            return 2 * (e.get(0) * e.get(1) + e.get(1) * e.get(2) + e.get(2) * e.get(0)) / 64;
        }
    }

    private static final class Job {
        private final Brick brick;
        private final Tile tile;
        // Per axis, the scratch origin of its face pair: x | y << 16.
        private final int[] scratch;
        private final List<Tile> candidates;
        private int firstCompare;
        private final int revision;

        private Job(Brick brick, Tile tile, int[] scratch, List<Tile> candidates) {
            this.brick = brick;
            this.revision = brick.revision;
            this.tile = tile;
            this.scratch = scratch;
            this.candidates = candidates;
        }
    }

    // Face pairs on shelves of their height in the bake scratch.
    private static final class Scratch {
        // Per shelf: y, height, next x.
        private final IntArrayList shelves = new IntArrayList();
        private int top;

        // The three pairs' origins, x | y << 16, or null (nothing placed) unless all fit.
        private int @Nullable [] place(int[] extent) {
            IntArrayList trial = new IntArrayList(shelves);
            int trialTop = top;
            int[] placed = new int[3];
            for (int a = 0; a < 3; a++) {
                int width = 2 * extent[(a + 1) % 3];
                int height = extent[(a + 2) % 3];
                int at = -1;
                for (int i = 0; i < trial.size() && at < 0; i += 3) {
                    if (trial.getInt(i + 1) == height && trial.getInt(i + 2) + width <= SCRATCH_SIZE) {
                        at = i;
                    }
                }
                if (at < 0) {
                    if (trialTop + height > SCRATCH_SIZE) {
                        return null;
                    }
                    at = trial.size();
                    trial.add(trialTop);
                    trial.add(height);
                    trial.add(0);
                    trialTop += height;
                }
                placed[a] = trial.getInt(at + 2) | trial.getInt(at) << 16;
                trial.set(at + 2, trial.getInt(at + 2) + width);
            }
            shelves.clear();
            shelves.addAll(trial);
            top = trialTop;
            return placed;
        }
    }

    private static final class Batch {
        private final Model model;
        private final BlockState fallbackState;
        private final int index;
        private final int bias;
        private final int typeId;
        private final int strideUints;
        private final float meshSwitch;
        private final Vector4fc sphere;
        // Model-space box of every mesh.
        private final float[] boxMin = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        private final float[] boxMax = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        private final List<MeshPool.PooledMesh> meshes = new ArrayList<>();
        private final List<Material> materials = new ArrayList<>();
        // Pages that may have a free lane, validated when popped.
        private final IntArrayList open = new IntArrayList();
        private int pages;
        private int baseInstance;
        private int liveRows;
        private final Set<Part> liveParts = new LinkedHashSet<>();

        private Batch(int index, BatchKey key, MeshPool meshPool) {
            model = key.model();
            fallbackState = key.fallbackState();
            this.index = index;
            bias = key.bias();
            typeId = InstanceTypeIds.id(key.type());
            strideUints = RECORD_UINTS + (key.type().layout().byteSize() + 3) / 4;
            sphere = key.model().boundingSphere();
            long triangles = 0;
            for (Model.ConfiguredMesh configured : key.model().meshes()) {
                MeshPool.PooledMesh mesh = meshPool.alloc(configured.mesh());
                mesh.acquire();
                meshes.add(mesh);
                materials.add(configured.material());
                triangles += configured.mesh().indexCount() / 3;
                FarBounds.growBounds(configured.mesh(), boxMin, boxMax);
            }
            // Mesh while one block is at least 16 px (baked impostor faces would magnify) and the triangles average
            // at least 4 px over the bounding sphere's disc.
            double radius = Math.max(sphere.w(), 1 / 16f);
            meshSwitch = (float) Math.max(16, Math.sqrt(4 * triangles / (Math.PI * radius * radius)));
        }
    }
}
