package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.iris.mixin.BlendModeOverrideAccessor;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.compile.patches.DeferredHostShaders;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.iris.engine.FarFrontDepth;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.Iris;

import java.util.function.IntUnaryOperator;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.lwjgl.system.MemoryStack;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;

import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;


/**
 * Shared post-pack graph. Forward attachments retain their admitted opacity/metadata contract; deferred captures
 * join the pack's material pool before the nearest native-water or retained surface publishes its G-buffer/depth.
 */
public final class FarHostOit implements AutoCloseable {
    public static final String SUFFIX = "/flywheel_host_oit";
    private final ContractProperties properties;
    private final int[] buffers;
    private final BlendMode[] blends;
    private final @Nullable GuestOitTargets targets;
    private final @Nullable AsyncGlProgram composite;
    private final @Nullable DeferredOitProfile deferred;
    private int storageOffset;
    private final Matrix4f hostProjection = new Matrix4f();
    private final Matrix4f hostModelView = new Matrix4f();
    private final Matrix4f mainFromHost = new Matrix4f();
    private int hostOpaque;
    private int mainOpaque;
    private boolean reverseZ;
    private float rasterScale = 1;
    private float mainOpaqueScaleX = 1;
    private float mainOpaqueScaleY = 1;
    private @Nullable Runnable nativeTarget;
    private int nativeFramebuffer;
    private int publicationFramebuffer;
    private int[] publicationAttachments;
    private int[] publicationDrawBuffers;
    private int publicationDepth;
    private int publicationFront;
    private final int vertexArray = GL45C.glCreateVertexArrays();
    private int pass = -1;
    private int width;
    private int height;
    private int textureBase;
    private final Matrix4f inverseProjection = new Matrix4f();
    private boolean zeroToOne;
    private final IntOpenHashSet pack2d = new IntOpenHashSet(new int[]{0, 1, 2, 8, 9});
    private final IntOpenHashSet packArrays = new IntOpenHashSet();
    private final Map<Integer, IntOpenHashSet> packExtras = new HashMap<>();
    private FarGlState.TextureBindings[] extraTextureBindings = new FarGlState.TextureBindings[0];
    private int[] textureUnits = new int[0];
    private int[] arrayTextureUnits = new int[0];
    private int scopeTextureBase = -1;

    private FarHostOit(String label, int[] buffers, BlendMode[] blends, ContractProperties properties) {
        deferred = null;
        this.blends = blends;
        this.properties = properties;
        this.buffers = buffers;
        int[] formats = new int[buffers.length];
        for (int i = 0; i < buffers.length; i++)
            formats[i] = properties.oit(false).accumulate(buffers[i]).coefficient()
                    == ContractProperties.Accumulate.FRONTMOST
                    ? properties.oit(false).accumulate(buffers[i]).format().getGlFormat() : GL30C.GL_RGBA32F;
        targets = new GuestOitTargets(properties.oit(false).ranks(), formats, null);
        var vertex = new Compilation();
        vertex.version(GlslVersion.V430);
        vertex.appendComponent(new RawSource("host OIT fullscreen", """
                void main() {
                    vec2 corner=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                    gl_Position=vec4(corner*2.0-1.0,0.0,1.0);
                }
                """));
        var fragment = new Compilation();
        fragment.version(GlslVersion.V430);
        fragment.appendComponent(new RawSource("host OIT composite",
                GuestOitCodegen.compositeBody(
                        new GuestShaders.OitSpec(null, properties.oit(false), buffers, false))));
        composite = AsyncGlProgram.graphics("iris/host_oit/" + label, vertex, fragment, _ -> {
        });
    }

    private FarHostOit(int[] buffers, DeferredOitProfile deferred) {
        this.deferred = deferred;
        this.buffers = buffers;
        blends = new BlendMode[buffers.length];
        properties = ContractProperties.EMPTY;
        targets = null;
        composite = null;
        if (publishesFront()) {
            int limit = Math.min(GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS),
                    GL11C.glGetInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS));
            if (buffers.length >= limit)
                throw new BackendUnavailableException(
                        "Native LoD material outputs leave no canonical-front attachment");
            publicationAttachments = new int[buffers.length];
            publicationDrawBuffers = new int[buffers.length + 1];
            for (int i = 0; i < publicationDrawBuffers.length; i++)
                publicationDrawBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
        }
    }

    private boolean publishesFront() {
        return deferred != null && deferred != DeferredOitProfile.ITERATION;
    }

    public static FarHostOit deferred(int[] buffers, DeferredOitProfile profile) {
        return new FarHostOit(buffers, profile);
    }

    public void storageOffset(int offset) {
        storageOffset = offset;
    }

    public boolean deferred() {
        return deferred != null;
    }

    public boolean packed() {
        return deferred == DeferredOitProfile.SEUS || deferred == DeferredOitProfile.SEUS_HRR3
                || deferred == DeferredOitProfile.SEUS_GFME;
    }

    /**
     * Applies the pack's lower-left quadrant raster transform while retaining its native depth projection.
     */
    public Matrix4f rasterProjection(Matrix4fc projection) {
        return packed() ? new Matrix4f().m00(0.5f).m11(0.5f).m30(-0.5f).m31(-0.5f).mul(projection)
                : new Matrix4f(projection);
    }

    public int passCount() {
        return deferred == null ? 3 : 2;
    }

    /**
     * The frame boundary supplies both opaque snapshots and explicit projection conventions before replay.
     */
    public void deferredView(int hostOpaque, int mainOpaque, Matrix4fc mainProjection, Matrix4fc mainModelView,
                             Matrix4fc hostModelView, boolean reverseZ, float rasterScale,
                             float mainOpaqueScaleX, float mainOpaqueScaleY) {
        this.hostOpaque = hostOpaque;
        this.mainOpaque = mainOpaque;
        this.hostModelView.set(hostModelView);
        mainFromHost.set(mainProjection).mul(mainModelView).mul(new Matrix4f(hostModelView).invert());
        this.reverseZ = reverseZ;
        this.rasterScale = rasterScale;
        this.mainOpaqueScaleX = mainOpaqueScaleX;
        this.mainOpaqueScaleY = mainOpaqueScaleY;
    }

    /**
     * Native host programs share a previously admitted contract only when they expose its same target set.
     */
    public static @Nullable FarHostOit create(ProgramSource source, IrisRenderingPipeline pipeline) {
        var profile = source.getParent().flywheel$deferredOit();
        if (profile != null) return deferred(source.getDirectives().getDrawBuffers(), profile);
        var admitted = GuestProgramResolver.oitProperties(pipeline, false);
        var authored = admitted == null ? ContractProperties.EMPTY.oit(false) : admitted.oit(false);
        boolean sameTargets = Arrays.stream(source.getDirectives().getDrawBuffers())
                                    .allMatch(buffer -> authored.declared(buffer) != null);
        return create(source, sameTargets ? authored : ContractProperties.EMPTY.oit(false), sameTargets);
    }

    /**
     * Only source-over outputs with a checked attachment contract enter this graph.
     */
    static @Nullable FarHostOit create(ProgramSource source, ContractProperties.Oit authored, boolean certified) {
        var buffers = source.getDirectives().getDrawBuffers();
        var blends = new BlendMode[buffers.length];
        var global = source.getDirectives().getBlendModeOverride().orElse(null);
        for (int slot = 0; slot < buffers.length; slot++) {
            blends[slot] = global == null ? new BlendMode(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE,
                    GL11C.GL_ONE_MINUS_SRC_ALPHA) : ((BlendModeOverrideAccessor) global).flywheel$blendMode();
            for (var entry : source.getDirectives().getBufferBlendOverrides())
                if (entry.index() == buffers[slot]) blends[slot] = entry.blendMode();
        }
        var formats = new InternalTextureFormat[buffers.length];
        for (int slot = 0; slot < buffers.length; slot++)
            formats[slot] = source.getParent().getPackDirectives()
                                  .getRenderTargetDirectives().getRenderTargetSettings().get(buffers[slot])
                                  .getInternalFormat();
        var properties = ContractProperties.nativeForwardOit(source.getName(),
                ContractProperties.hostEffects(source.getVertexSource().orElseThrow(), "main"),
                ContractProperties.hostEffects(source.getFragmentSource().orElseThrow(), "main"), buffers, formats,
                blends, certified, authored);
        return properties == null || properties.oit(false).ranks().length == 0 ? null
                : new FarHostOit(source.getName(), buffers, blends, properties);
    }

    static @Nullable FarHostOit create(String label, String vertex, String fragment, int[] buffers,
                                       InternalTextureFormat[] formats,
                                       BlendMode[] blends, ContractProperties.Oit authored) {
        boolean certified = Arrays.stream(buffers).allMatch(buffer -> authored.declared(buffer) != null);
        var properties = ContractProperties.nativeForwardOit(label, vertex,
                ContractProperties.hostEffects(fragment, "voxy_emitFragment"), buffers, formats, blends,
                certified, certified ? authored : ContractProperties.EMPTY.oit(false));
        return properties == null || properties.oit(false).ranks().length == 0 ? null
                : new FarHostOit(label, buffers, blends, properties);
    }

    /**
     * Records linked sampler targets once at native construction or asynchronous publication, never per draw.
     */
    public void registerSamplers(GlProgram program) {
        boolean changed = false;
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int count = GL20C.glGetProgrami(program.handle(), GL20C.GL_ACTIVE_UNIFORMS);
            for (int index = 0; index < count; index++) {
                String name = GL20C.glGetActiveUniform(program.handle(), index, size, type);
                if (name.startsWith("_flw_")) continue;
                IntOpenHashSet units = switch (type.get(0)) {
                    case GL20C.GL_SAMPLER_2D, GL20C.GL_SAMPLER_2D_SHADOW,
                         GL30C.GL_INT_SAMPLER_2D, GL30C.GL_UNSIGNED_INT_SAMPLER_2D -> pack2d;
                    case GL30C.GL_SAMPLER_2D_ARRAY, GL30C.GL_SAMPLER_2D_ARRAY_SHADOW,
                         GL30C.GL_INT_SAMPLER_2D_ARRAY, GL30C.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> packArrays;
                    default -> {
                        int target = switch (type.get(0)) {
                            case GL20C.GL_SAMPLER_1D, GL20C.GL_SAMPLER_1D_SHADOW,
                                 GL30C.GL_INT_SAMPLER_1D, GL30C.GL_UNSIGNED_INT_SAMPLER_1D -> GL11C.GL_TEXTURE_1D;
                            case GL20C.GL_SAMPLER_3D, GL30C.GL_INT_SAMPLER_3D,
                                 GL30C.GL_UNSIGNED_INT_SAMPLER_3D -> GL12C.GL_TEXTURE_3D;
                            case GL20C.GL_SAMPLER_CUBE, GL30C.GL_SAMPLER_CUBE_SHADOW,
                                 GL30C.GL_INT_SAMPLER_CUBE, GL30C.GL_UNSIGNED_INT_SAMPLER_CUBE ->
                                    GL13C.GL_TEXTURE_CUBE_MAP;
                            case GL31C.GL_SAMPLER_BUFFER, GL31C.GL_INT_SAMPLER_BUFFER,
                                 GL31C.GL_UNSIGNED_INT_SAMPLER_BUFFER -> GL31C.GL_TEXTURE_BUFFER;
                            case GL31C.GL_SAMPLER_2D_RECT, GL31C.GL_SAMPLER_2D_RECT_SHADOW,
                                 GL31C.GL_INT_SAMPLER_2D_RECT, GL31C.GL_UNSIGNED_INT_SAMPLER_2D_RECT ->
                                    GL31C.GL_TEXTURE_RECTANGLE;
                            case GL30C.GL_SAMPLER_1D_ARRAY, GL30C.GL_SAMPLER_1D_ARRAY_SHADOW,
                                 GL30C.GL_INT_SAMPLER_1D_ARRAY, GL30C.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY ->
                                    GL30C.GL_TEXTURE_1D_ARRAY;
                            case GL32C.GL_SAMPLER_2D_MULTISAMPLE, GL32C.GL_INT_SAMPLER_2D_MULTISAMPLE,
                                 GL32C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE -> GL32C.GL_TEXTURE_2D_MULTISAMPLE;
                            case GL32C.GL_SAMPLER_2D_MULTISAMPLE_ARRAY, GL32C.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                                 GL32C.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY ->
                                    GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
                            case GL40C.GL_SAMPLER_CUBE_MAP_ARRAY, GL40C.GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW,
                                 GL40C.GL_INT_SAMPLER_CUBE_MAP_ARRAY, GL40C.GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY ->
                                    GL40C.GL_TEXTURE_CUBE_MAP_ARRAY;
                            default -> 0;
                        };
                        yield target == 0 ? null : packExtras.computeIfAbsent(target, _ -> new IntOpenHashSet());
                    }
                };
                if (units == null) continue;
                for (int element = 0; element < size.get(0); element++) {
                    String elementName = size.get(0) == 1 ? name : name.replace("[0]", "[" + element + "]");
                    int location = GL20C.glGetUniformLocation(program.handle(), elementName);
                    if (location >= 0) changed |= units.add(GL20C.glGetUniformi(program.handle(), location));
                }
            }
        }
        if (changed) scopeTextureBase = -1;
    }

    private void scope(int base) {
        if (scopeTextureBase == base) return;
        var twoD = new IntOpenHashSet(pack2d);
        var arrays = new IntOpenHashSet(packArrays);
        int sets = properties.oit(false).ranks().length;
        twoD.add(base);
        twoD.add(base + 1);
        for (int set = 0; set < sets; set++) {
            arrays.add(base + 2 + set);
            arrays.add(1 + set);
        }
        twoD.add(0);
        for (int slot = 0; slot < buffers.length; slot++) twoD.add(sets + 1 + slot);
        textureUnits = twoD.toIntArray();
        arrayTextureUnits = arrays.toIntArray();
        Arrays.sort(textureUnits);
        Arrays.sort(arrayTextureUnits);
        extraTextureBindings = packExtras.entrySet().stream().map(entry -> new FarGlState.TextureBindings(
                                                 entry.getKey(), bindingQuery(entry.getKey()), entry.getValue().toIntArray()))
                                         .toArray(FarGlState.TextureBindings[]::new);
        scopeTextureBase = base;
    }

    private static int bindingQuery(int target) {
        return switch (target) {
            case GL11C.GL_TEXTURE_1D -> GL11C.GL_TEXTURE_BINDING_1D;
            case GL12C.GL_TEXTURE_3D -> GL12C.GL_TEXTURE_BINDING_3D;
            case GL13C.GL_TEXTURE_CUBE_MAP -> GL13C.GL_TEXTURE_BINDING_CUBE_MAP;
            case GL31C.GL_TEXTURE_BUFFER -> GL31C.GL_TEXTURE_BINDING_BUFFER;
            case GL31C.GL_TEXTURE_RECTANGLE -> GL31C.GL_TEXTURE_BINDING_RECTANGLE;
            case GL30C.GL_TEXTURE_1D_ARRAY -> GL30C.GL_TEXTURE_BINDING_1D_ARRAY;
            case GL32C.GL_TEXTURE_2D_MULTISAMPLE -> GL32C.GL_TEXTURE_BINDING_2D_MULTISAMPLE;
            case GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> GL32C.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY;
            case GL40C.GL_TEXTURE_CUBE_MAP_ARRAY -> GL40C.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY;
            default -> throw new IllegalArgumentException("Unsupported sampler target " + target);
        };
    }

    public int producerTextureCount() {
        return deferred == null ? 2 + properties.oit(false).ranks().length : 2;
    }

    public boolean ready() {
        return deferred != null || composite.get() != null;
    }

    /**
     * Converts an already patched pack fragment. The producer evaluates its original body once per pass; depth
     * reconstruction comes from the explicit host view, including hardware forward/reverse depth conventions.
     */
    public String fragment(String fragment, int producerPass) {
        return fragment(fragment, producerPass, false);
    }

    public String fragment(String fragment, int producerPass, boolean retained) {
        fragment = JcppProcessor.glslPreprocessSource(fragment, List.of());
        if (deferred != null) return DeferredHostShaders.fragment(fragment, deferred, producerPass, retained,
                storageOffset, publishesFront() ? buffers.length : -1);
        var demoted = GuestShaders.demoteForOit(fragment);
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            root.rename("_flw_irisMain", "_flw_farHostPackMain");
            boolean pixelHits = retained && root.identifierIndex.has("_flw_farHitIndex");
            var library = new Compilation();
            library.version(GuestShaders.glslVersion());
            library.appendComponent(new RawSource("host OIT fragment coordinate", "vec4 _flw_farHostFragCoord;"));
            library.appendComponent(new RawSource("host OIT view", """
                    uniform mat4 _flw_farHostModelView;
                    uniform mat4 _flw_farHostInverseProjection;
                    uniform vec2 _flw_farHostSize;
                    uniform bool _flw_farHostZeroToOne;
                    float _flw_oitViewZ;
                    void _flw_irisMain() {
                    """ + (retained ? "" : """
                        vec2 uv=gl_FragCoord.xy/_flw_farHostSize;
                        float depth=gl_FragCoord.z;
                        if(!_flw_farHostZeroToOne) depth=depth*2.0-1.0;
                        vec4 view=_flw_farHostInverseProjection*vec4(uv*2.0-1.0,depth,1.0);
                        _flw_oitViewZ=view.z/view.w;
                    """) + "_flw_farHostPackMain();\n" + (retained
                    ? "_flw_oitViewZ=(_flw_farHostModelView*vec4(_flw_farLayerCenter,1.0)).z;\n" : "")
                    + "_flw_farHostFragCoord=" + (pixelHits ? "_flw_farFragCoord" : "gl_FragCoord") + ";\n}"));
            library.appendComponent(new RawSource("host OIT equations",
                    FlwPrograms.SOURCES.get(GuestOitCodegen.LIBRARY).source()));
            var producer = parser.parseTranslationUnit(root, GuestShaders.versionPreamble()
                    + GuestOitCodegen.producer(new GuestShaders.OitSpec(OitPass.values()[producerPass],
                    properties.oit(false), buffers, false), demoted.outputs()));
            for (var reference : root.nodeIndex.getStream(ReferenceExpression.class).toList())
                if (reference.hasAncestor(producer) && reference.getIdentifier().getName().equals("gl_FragCoord"))
                    reference.getIdentifier().setName("_flw_farHostFragCoord");
            // Macro declarations belong to the translation unit, not its child nodes: expand before grafting them.
            var declarations = parser.parseTranslationUnit(root,
                    JcppProcessor.glslPreprocessSource(library.assembledSource(), List.of()));
            tree.injectNodes(ASTInjectionPoint.END,
                    declarations.getChildren().stream().map(node -> node.cloneInto(root)).toList());
            declarations.detachAndDelete();
            tree.injectNodes(ASTInjectionPoint.END,
                    producer.getChildren().stream().map(node -> node.cloneInto(root)).toList());
            producer.detachAndDelete();
        });
        return parser.transform(demoted.source());
    }

    /**
     * All producers must be ready before entry. replayNativeWater and drawRetained are invoked for every pass with
     * identical geometry; neither may consume the source on the first invocation.
     */
    public void render(int depth, int width, int height, Matrix4fc projection, boolean zeroToOne, int textureBase,
                       int nativeFramebuffer, IntUnaryOperator nativeColor,
                       Runnable replayNativeWater, Runnable drawRetained, Runnable bindNativeTarget) {
        scope(textureBase);
        try (var _ = FarGlState.capture(textureUnits, arrayTextureUnits, extraTextureBindings)) {
            this.width = width;
            this.height = height;
            this.zeroToOne = zeroToOne;
            this.textureBase = textureBase;
            hostProjection.set(projection);
            inverseProjection.set(projection).invert();
            nativeTarget = bindNativeTarget;
            this.nativeFramebuffer = nativeFramebuffer;
            if (publishesFront()) preparePublication(depth, nativeColor);
            if (targets != null) targets.prepare(depth, width, height);
            for (pass = 0; pass < passCount(); pass++) {
                bindProducer();
                replayNativeWater.run();
                bindProducer();
                drawRetained.run();
            }
            pass = -1;
            bindHostTarget();
            if (deferred == null) resolve();
            else {
                raster(true, true);
                for (int slot = 0; slot < buffers.length; slot++) targetState(slot, 15, false);
            }
        } finally {
            nativeTarget = null;
            pass = -1;
        }
    }

    public int pass() {
        return pass;
    }

    /**
     * Called after native host framebuffer/blend setup and after every program bind inside a producer.
     */
    public void bindProducer() {
        if (deferred != null) {
            bindHostTarget();
            raster(pass == 1, pass == 1);
            for (int slot = 0; slot < buffers.length; slot++) targetState(slot, pass == 0 ? 0 : 15, false);
            if (publishesFront()) targetState(buffers.length, pass == 1 ? 15 : 0, false);
            return;
        }
        int framebuffer = switch (pass) {
            case 0 -> targets.depthRangeFbo();
            case 1 -> targets.coefficientsFbo();
            case 2 -> targets.accumulateFbo();
            default -> throw new IllegalStateException("Host OIT producer outside its replay");
        };
        GlStateManager.writeFbo = -1;
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        raster(true, false);
        GlStateManager._blendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
        GlStateManager._blendEquationSeparate(pass == 0 ? GL14C.GL_MAX : GL14C.GL_FUNC_ADD,
                pass == 0 ? GL14C.GL_MAX : GL14C.GL_FUNC_ADD);
        int attachments = pass == 0 ? 1 : pass == 2 ? buffers.length
                : Arrays.stream(properties.oit(false).ranks()).map(GuestOitCodegen::layers).sum();
        for (int i = 0; i < attachments; i++) targetState(i, 15, true);
    }

    private void bindHostTarget() {
        GlStateManager.readFbo = -1;
        GlStateManager.writeFbo = -1;
        nativeTarget.run();
        // Reconcile raw host setup with the known draw target, without reading mutable GL state.
        GlStateManager.readFbo = -1;
        GlStateManager.writeFbo = -1;
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,
                pass == 1 && publishesFront() ? publicationFramebuffer : nativeFramebuffer);
    }

    private void preparePublication(int depth, IntUnaryOperator nativeColor) {
        if (publicationFramebuffer == 0) {
            publicationFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffers(publicationFramebuffer, publicationDrawBuffers);
        }
        for (int slot = 0; slot < buffers.length; slot++) {
            int color = nativeColor.applyAsInt(slot);
            if (publicationAttachments[slot] == color) continue;
            GL45C.glNamedFramebufferTexture(publicationFramebuffer, GL30C.GL_COLOR_ATTACHMENT0 + slot, color, 0);
            publicationAttachments[slot] = color;
        }
        if (publicationDepth != depth) {
            GL45C.glNamedFramebufferTexture(publicationFramebuffer, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
            publicationDepth = depth;
        }
        var pipeline = (IrisRenderingPipelineAccessor) Iris.getPipelineManager().getPipelineNullable();
        int front = FarFrontDepth.texture(pipeline.flywheel$renderTargets());
        if (publicationFront != front) {
            GL45C.glNamedFramebufferTexture(publicationFramebuffer, GL30C.GL_COLOR_ATTACHMENT0 + buffers.length, front,
                    0);
            publicationFront = front;
        }
    }

    private void raster(boolean depthEnabled, boolean depthWrite) {
        // Host setup bypasses Blaze3D caches. Force the graph's known state through its tracked entry points.
        GlStateManager.SCISSOR.mode.enabled = true;
        GlStateManager.CULL.enable.enabled = true;
        GlStateManager.DEPTH.mode.enabled = !depthEnabled;
        GlStateManager.DEPTH.func = -1;
        GlStateManager.DEPTH.mask = !depthWrite;
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._disableScissorTest();
        GlStateManager._disableCull();
        if (depthEnabled) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();
        GlStateManager._depthFunc(reverseZ ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        GlStateManager._depthMask(depthWrite);
        #if MC >= 26.3
        var blend = GlStateManager.BLEND;
        #else
        var blend = GlStateManager.BLEND[0];
        #endif
        blend.srcRgb = -1;
        blend.modeRgb = -1;
    }

    private static void targetState(int slot, int mask, boolean blendEnabled) {
        GlStateManager.COLOR_MASK[slot] = -1;
        GlStateManager._colorMask(slot, mask);
        #if MC >= 26.3
        GlStateManager.BLEND_ENABLE[slot] = !blendEnabled;
        if (blendEnabled) GlStateManager._enableBlend(slot);
        else GlStateManager._disableBlend(slot);
        #else
        // 26.2: the indexed-looking tracked setters change GL_BLEND globally; set target zero before indexed targets.
        if (slot == 0) {
            GlStateManager.BLEND[0].mode.enabled = !blendEnabled;
            if (blendEnabled) GlStateManager._enableBlend(0);
            else GlStateManager._disableBlend(0);
        } else {
            if (blendEnabled) GL30C.glEnablei(GL11C.GL_BLEND, slot);
            else GL30C.glDisablei(GL11C.GL_BLEND, slot);
        }
        #endif
    }

    public void bindProgram(GlProgram program) {
        GlStateManager._glUseProgram(program.handle());
        if (deferred != null) {
            program.setMat4("_flw_farDeferredInverseHost", inverseProjection);
            program.setMat4("_flw_farDeferredHostProjection", hostProjection);
            program.setMat4("_flw_farDeferredMainFromHost", mainFromHost);
            program.setMat4("_flw_farDeferredModelView", hostModelView);
            program.setVec2("_flw_farDeferredSize", width, height);
            var mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            program.setVec2("screenSize", mainTarget.width, mainTarget.height);
            program.setBool("_flw_farDeferredHostZeroToOne", zeroToOne);
            program.setBool("_flw_farDeferredHostReverseZ", reverseZ);
            program.setFloat("_flw_farDeferredRasterScale", rasterScale);
            program.setVec2("_flw_farDeferredMainOpaqueScale", mainOpaqueScaleX, mainOpaqueScaleY);
            texture(program, "_flw_farDeferredHostOpaque", textureBase, hostOpaque, GL11C.GL_TEXTURE_2D);
            texture(program, "_flw_farDeferredMainOpaque", textureBase + 1, mainOpaque, GL11C.GL_TEXTURE_2D);
            program.setBool("flw_oitActive", true);
            bindProducer();
            return;
        }
        program.setMat4("_flw_farHostInverseProjection", inverseProjection);
        program.setVec2("_flw_farHostSize", width, height);
        program.setBool("_flw_farHostZeroToOne", zeroToOne);
        int unit = textureBase;
        texture(program, "_flw_depthRange", unit++, targets.depthRange(), GL11C.GL_TEXTURE_2D);
        texture(program, "_flw_blueNoise", unit++,
                ((GlTexture) NoiseTextures.BLUE_NOISE.getTexture()).glId(),
                GL11C.GL_TEXTURE_2D);
        for (int set = 0; set < properties.oit(false).ranks().length; set++)
            texture(program, "_flw_coefficients" + set, unit++, targets.coefficients(set), GL30C.GL_TEXTURE_2D_ARRAY);
        bindProducer();
    }

    private void resolve() {
        var program = composite.get();
        GlStateManager._glUseProgram(program.handle());
        GlStateManager._glBindVertexArray(vertexArray);
        raster(false, false);
        int unit = 0;
        texture(program, "_flw_depthRange", unit++, targets.depthRange(), GL11C.GL_TEXTURE_2D);
        for (int set = 0; set < properties.oit(false).ranks().length; set++)
            texture(program, "_flw_coefficients" + set, unit++, targets.coefficients(set), GL30C.GL_TEXTURE_2D_ARRAY);
        for (int slot = 0; slot < buffers.length; slot++) {
            texture(program, "_flw_accumulate" + slot, unit++, targets.accumulate(slot), GL11C.GL_TEXTURE_2D);
            BlendMode blend = blends[slot];
            targetState(slot, 15, blend != null);
            if (blend != null) {
                if (slot == 0) {
                    GlStateManager._blendFuncSeparate(blend.srcRgb(), blend.dstRgb(), blend.srcAlpha(),
                            blend.dstAlpha());
                    GlStateManager._blendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
                } else {
                    GL40C.glBlendFuncSeparatei(slot, blend.srcRgb(), blend.dstRgb(), blend.srcAlpha(),
                            blend.dstAlpha());
                    GL40C.glBlendEquationSeparatei(slot, GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
                }
            }
        }
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    }

    private static void texture(GlProgram program, String name, int unit, int texture, int target) {
        if (program.getUniformLocation(name) < 0) return;
        program.setSamplerBinding(name, unit);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        if (target == GL11C.GL_TEXTURE_2D && unit < 12) GlStateManager._bindTexture(texture);
        else GL11C.glBindTexture(target, texture);
        GL33C.glBindSampler(unit, 0);
    }

    @Override
    public void close() {
        if (targets != null) targets.delete();
        if (composite != null) composite.close();
        GL30C.glDeleteVertexArrays(vertexArray);
        if (publicationFramebuffer != 0) GL30C.glDeleteFramebuffers(publicationFramebuffer);
    }
}
