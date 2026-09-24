package dev.engine_room.flywheel.iris.engine;

import com.google.common.primitives.Ints;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.mixin.ShaderStorageBufferAccessor;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Sundial's depth-layered deferred replay. Render-thread resources are released with the owning CompositeRenderer.
 * Far layers shade only stencil-masked tiles holding that many layers (dilated past the passes' neighbour reads).
 * Loop bound, node pool and snapshot targets follow a fenced readback of earlier frames' counters; a frame beyond
 * them renders natively, as on pool overflow.
 */
public final class DeferredOitRenderer implements AutoCloseable {
    private static final int FIRST_TEXTURE = 10;
    private static final int MAX_LAYERS = 64;
    private static final int READBACK_SLOTS = 3;
    // Readbacks below the bound's bin before it halves / with the pool over twice its target before it shrinks.
    private static final int SHRINK_READBACKS = 60;
    // Readbacks at bound 1 before the snapshot targets are released.
    private static final int RELEASE_READBACKS = 600;
    private static final int NODE_BYTES = 32;
    private static final long MIN_POOL_NODES = 1 << 16;
    // 8 MiB.
    private static final long POOL_GRANULE = 1 << 18;
    private static final String VERTEX = DeferredOitProfile.resource("layer_fullscreen.vert");
    private static final String FRAGMENT = DeferredOitProfile.resource("layer_materialize.frag");

    private final RenderTargets targets;
    private final DeferredCompositePass first;
    private final Program firstProgram;
    private final Set<Integer> readAlt;
    private final DeferredCompositePass[] layerPasses;
    private final int[] farLocations;
    private final int widthLocation;
    private final int layerLocation;
    private final int maskLayerLocation;
    private final int boundLocation;
    private final @Nullable Program postProgram;
    private final CustomUniforms customUniforms;
    private final ShaderStorageBuffer nodes;
    private final int[] saved = new int[7];
    private final int[] readback = new int[READBACK_SLOTS];
    private final ByteBuffer[] readbackData = new ByteBuffer[READBACK_SLOTS];
    private final long[] readbackFence = new long[READBACK_SLOTS];
    private int readbackSlot;
    private int layerBound = 1;
    private int shrinkReadbacks;
    private int idleReadbacks;
    private long poolNodes;
    private int poolShrinks;
    private int program;
    private int copyProgram;
    private int depthProgram;
    private int commandsProgram;
    private int tilesProgram;
    private int maskProgram;
    private int commands;
    private int stencil;
    private int maskFbo;
    private int tilesX;
    private int tilesY;
    private int width;
    private int height;
    private int fbo;
    private int savedWeather;
    private int copyFbo;
    private int depthFbo;
    private boolean modifiedBackDepth;
    private int attachedDepth;
    private int attachedOpaqueDepth;
    private long textureBytes;

    private DeferredOitRenderer(RenderTargets targets, DeferredCompositePass first, List<?> passes,
                                CustomUniforms uniforms, ShaderStorageBuffer nodes) {
        this.targets = targets;
        this.nodes = nodes;
        poolNodes = GL45C.glGetNamedBufferParameteri64(nodes.getId(), GL15C.GL_BUFFER_SIZE) / NODE_BYTES;
        this.first = first;
        firstProgram = first.flywheel$program();
        readAlt = first.flywheel$readAlt();
        customUniforms = uniforms;
        var layers = new ArrayList<DeferredCompositePass>();
        Program post = null;
        for (Object entry : passes) {
            DeferredCompositePass pass = (DeferredCompositePass) entry;
            String name = pass.flywheel$name();
            if (!name.matches("composite[0-9]+")) continue;
            int index = Integer.parseInt(name.substring("composite".length()));
            if (index >= 1 && index <= 6) layers.add(pass);
            else if (index > 6 && post == null) post = pass.flywheel$program();
        }
        postProgram = post;
        layerPasses = layers.toArray(DeferredCompositePass[]::new);
        farLocations = new int[layerPasses.length];
        for (int i = 0; i < layerPasses.length; i++) {
            farLocations[i] = GL20C.glGetUniformLocation(layerPasses[i].flywheel$program().getProgramId(),
                    "flw_oitFarLayer");
        }
        try {
            program = compile(VERTEX, FRAGMENT);
            copyProgram = compile(VERTEX, DeferredOitProfile.resource("layer_copy.frag"));
            depthProgram = compile(VERTEX, DeferredOitProfile.resource("layer_depth.frag"));
            GL45C.glProgramUniform1i(copyProgram, GL20C.glGetUniformLocation(copyProgram, "color"), FIRST_TEXTURE);
            GL45C.glProgramUniform1i(copyProgram, GL20C.glGetUniformLocation(copyProgram, "weather"),
                    FIRST_TEXTURE + 1);
            GL45C.glProgramUniform1i(depthProgram, GL20C.glGetUniformLocation(depthProgram, "depth"), FIRST_TEXTURE);
            GL45C.glProgramUniform1i(depthProgram, GL20C.glGetUniformLocation(depthProgram, "color"),
                    FIRST_TEXTURE + 1);
            commandsProgram = compute(DeferredOitProfile.resource("layer_commands.comp"));
            tilesProgram = compute(DeferredOitProfile.resource("layer_tiles.comp"));
            maskProgram = compile(VERTEX, DeferredOitProfile.resource("layer_mask.frag"));
            commands = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(commands, MAX_LAYERS * 20L, 0);
            boundLocation = GL20C.glGetUniformLocation(commandsProgram, "bound");
            int flags = GL30C.GL_MAP_READ_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;
            for (int i = 0; i < READBACK_SLOTS; i++) {
                readback[i] = GL45C.glCreateBuffers();
                GL45C.glNamedBufferStorage(readback[i], 12, flags);
                readbackData[i] = GL45C.glMapNamedBufferRange(readback[i], 0, 12, flags);
            }
            String[] samplers = {"saved0", "saved1", "saved2", "savedDepth", "saved5", "unused", "savedCloud", "backDepth"};
            for (int i = 0; i < samplers.length; i++)
                GL45C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, samplers[i]), FIRST_TEXTURE + i);
            widthLocation = GL20C.glGetUniformLocation(program, "width");
            layerLocation = GL20C.glGetUniformLocation(program, "layerIndex");
            maskLayerLocation = GL20C.glGetUniformLocation(maskProgram, "layerIndex");
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /**
     * @param nodes the layer pool (pack binding 1); its GL buffer is replaced as demand changes.
     */
    public static @Nullable DeferredOitRenderer create(RenderTargets targets, List<?> passes, CustomUniforms uniforms,
                                                       ShaderStorageBuffer nodes) {
        for (Object entry : passes) {
            DeferredCompositePass pass = (DeferredCompositePass) entry;
            if (pass.flywheel$name().equals("composite1"))
                return new DeferredOitRenderer(targets, pass, passes, uniforms, nodes);
        }
        return null;
    }

    // 4 nodes/pixel.
    private static long maxPoolNodes(int width, int height) {
        return 4L * width * height;
    }

    private static void bindTexture(int unit, int texture) {
        GlStateManager._activeTexture(GL20C.GL_TEXTURE0 + FIRST_TEXTURE + unit);
        GlStateManager._bindTexture(texture);
        GL33C.glBindSampler(FIRST_TEXTURE + unit, 0);
    }

    private static int depthBytes(int texture) {
        return switch (GL45C.glGetTextureLevelParameteri(texture, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT)) {
            case GL30C.GL_DEPTH32F_STENCIL8 -> 8;
            case GL14C.GL_DEPTH_COMPONENT16 -> 2;
            default -> 4;
        };
    }

    private static void draw(int indexType, int layer) {
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, indexType, layer * 20L);
    }

    private static void requireComplete(int fbo) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Deferred framebuffer incomplete: " + Integer.toHexString(status));
        }
    }

    private static int compile(String vertex, String fragment) {
        return link(new int[]{GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER}, vertex, fragment);
    }

    private static int compute(String source) {
        return link(new int[]{GL43C.GL_COMPUTE_SHADER}, source);
    }

    private static int link(int[] types, String... sources) {
        int program = GlStateManager.glCreateProgram();
        int[] shaders = new int[types.length];
        int attached = 0;
        boolean linked = false;
        try {
            for (int i = 0; i < types.length; i++) {
                int shader = shaders[i] = GlStateManager.glCreateShader(types[i]);
                GL20C.glShaderSource(shader, sources[i]);
                GlStateManager.glCompileShader(shader);
                if (GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
                    throw new IllegalStateException("Deferred OIT shader: " + GL20C.glGetShaderInfoLog(shader));
                }
                GlStateManager.glAttachShader(program, shader);
                attached++;
            }
            GlStateManager.glLinkProgram(program);
            if (GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("Deferred OIT program: " + GL20C.glGetProgramInfoLog(program));
            }
            linked = true;
            return program;
        } finally {
            for (int i = 0; i < shaders.length; i++)
                if (shaders[i] != 0) {
                    if (i < attached) GL20C.glDetachShader(program, shaders[i]);
                    GlStateManager.glDeleteShader(shaders[i]);
                }
            if (!linked) GlStateManager.glDeleteProgram(program);
        }
    }

    public void before(Program current) {

        if (current == postProgram && modifiedBackDepth) {
            copy(saved[5], ((GlTexture) targets.getDepthTextureNoTranslucents()).glId());
            modifiedBackDepth = false;
        }
        if (current != firstProgram || !BackendManagerImpl.isBackendOn()) return;
        GlCompat.pushDebugGroup("flywheel:iris/deferred_layers");
        try {
            replay();
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    private void replay() {
        harvestReadbacks();
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
        if (readbackFence[readbackSlot] == 0) {
            GL45C.glCopyNamedBufferSubData(GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, 2),
                    readback[readbackSlot], 0, 0, 12);
            readbackFence[readbackSlot] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        readbackSlot = (readbackSlot + 1) % READBACK_SLOTS;
        // One layer: replay == the pack's own frame. More than the bound: native, as intended.
        if (layerBound == 1) return;
        int w = targets.getCurrentWidth();
        int h = targets.getCurrentHeight();
        int depth = ((GlTexture) targets.getDepthTexture()).glId();
        int opaqueDepth = ((GlTexture) targets.getDepthTextureNoTranslucents()).glId();
        boolean resized = w != width || h != height || depth != attachedDepth || opaqueDepth != attachedOpaqueDepth;
        if (resized) {
            releaseTargets();
            width = w;
            height = h;
            attachedDepth = depth;
            attachedOpaqueDepth = opaqueDepth;
            textureBytes = (long) w * h * (45 + depthBytes(depth) + depthBytes(opaqueDepth));
            tilesX = (w + 15) >> 4;
            tilesY = (h + 15) >> 4;
            for (int p : new int[]{tilesProgram, maskProgram}) {
                GL45C.glProgramUniform2i(p, GL20C.glGetUniformLocation(p, "tileCount"), tilesX, tilesY);
            }
            stencil = GL45C.glCreateRenderbuffers();
            GL45C.glNamedRenderbufferStorage(stencil, GL30C.GL_STENCIL_INDEX8, w, h);
            maskFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferRenderbuffer(maskFbo, GL30C.GL_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, stencil);
            GL45C.glNamedFramebufferDrawBuffer(maskFbo, GL11C.GL_NONE);
            fbo = GL45C.glCreateFramebuffers();
            copyFbo = GL45C.glCreateFramebuffers();
            depthFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(depthFbo, GL11C.GL_NONE);
            GL45C.glNamedFramebufferReadBuffer(depthFbo, GL11C.GL_NONE);
            savedWeather = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(savedWeather, 1, targets.get(4).getInternalFormat().getGlFormat(), w, h);
            for (int i = 0; i < saved.length; i++) {
                saved[i] = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
                int format = i == 3 || i == 5 ? GL45C.glGetTextureLevelParameteri(i == 3 ? depth : opaqueDepth,
                        0, GL11C.GL_TEXTURE_INTERNAL_FORMAT) : targets.get(i < 3 ? i : 5).getInternalFormat()
                                                                      .getGlFormat();
                GL45C.glTextureStorage2D(saved[i], 1, format, w, h);
                GL45C.glTextureParameteri(saved[i], GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL45C.glTextureParameteri(saved[i], GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            }
            GL45C.glNamedFramebufferDrawBuffers(fbo,
                    new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1, GL30C.GL_COLOR_ATTACHMENT2, GL30C.GL_COLOR_ATTACHMENT3});
            GL45C.glNamedFramebufferDrawBuffers(copyFbo,
                    new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        }
        for (int i = 0; i < 4; i++) {
            int source = i == 3 ? depth : readAlt.contains(i) ? targets.get(i).getAltTexture() : targets.get(i)
                                                                                                        .getMainTexture();
            copy(source, saved[i]);
            GL45C.glNamedFramebufferTexture(fbo, i == 3 ? GL30C.GL_DEPTH_ATTACHMENT : GL30C.GL_COLOR_ATTACHMENT0 + i,
                    source, 0);
        }
        int color5 = readAlt.contains(5) ? targets.get(5).getAltTexture() : targets.get(5).getMainTexture();
        GL45C.glNamedFramebufferTexture(fbo, GL30C.GL_COLOR_ATTACHMENT3, color5, 0);
        copy(color5, saved[6]);
        copy(readAlt.contains(4) ? targets.get(4).getAltTexture() : targets.get(4).getMainTexture(), savedWeather);
        copy(((GlTexture) targets.getDepthTextureNoTranslucents()).glId(), saved[5]);
        GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT0, saved[4], 0);
        GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT1,
                readAlt.contains(4) ? targets.get(4).getAltTexture() : targets.get(4).getMainTexture(), 0);
        GL45C.glNamedFramebufferTexture(depthFbo, GL30C.GL_DEPTH_ATTACHMENT,
                opaqueDepth, 0);
        DeferredCompositePass last = layerPasses[layerPasses.length - 1];
        int lastColor5 = last.flywheel$framebuffer().getColorAttachment(Ints.indexOf(last.flywheel$drawBuffers(), 5));
        boolean copyColor = lastColor5 != color5;
        GL45C.glNamedFramebufferTexture(depthFbo, GL30C.GL_COLOR_ATTACHMENT0, copyColor ? color5 : 0, 0);
        GL45C.glNamedFramebufferDrawBuffer(depthFbo, copyColor ? GL30C.GL_COLOR_ATTACHMENT0 : GL11C.GL_NONE);
        for (DeferredCompositePass pass : layerPasses) {
            GL45C.glNamedFramebufferRenderbuffer(pass.flywheel$framebuffer().getId(), GL30C.GL_STENCIL_ATTACHMENT,
                    GL30C.GL_RENDERBUFFER, stencil);
        }
        if (resized) {
            requireComplete(fbo);
            requireComplete(copyFbo);
            requireComplete(depthFbo);
            requireComplete(maskFbo);
            for (DeferredCompositePass pass : layerPasses) requireComplete(pass.flywheel$framebuffer().getId());
        }
        boolean depthEnabled = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean depthWrite = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ((GlBuffer) indices.getBuffer(6)).handle());
        int indexType = GlConst.toGl(indices.type());
        int previousStorage4 = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, 4);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, commands);
        GlStateManager._glUseProgram(commandsProgram);
        GL45C.glProgramUniform1ui(commandsProgram, boundLocation, layerBound);
        GL43C.glDispatchCompute(1, 1, 1);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, previousStorage4);
        GlStateManager._glUseProgram(tilesProgram);
        GL43C.glDispatchCompute((tilesX + 7) >> 3, (tilesY + 7) >> 3, 1);
        GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
        // Vanilla tracks no stencil state: raw, back to GL defaults after the far layers.
        GL45C.glClearNamedFramebufferiv(maskFbo, GL11C.GL_STENCIL, 0, new int[]{0});
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        {
            for (int layer = layerBound - 1; layer > 0; --layer) {
                prepareLayer(layer, color5, indexType);
                GlStateManager._disableDepthTest();
                GlStateManager._depthMask(false);
                // Tile sets only grow as the layer index falls: no per-layer clear.
                GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, maskFbo);
                GL11C.glStencilFunc(GL11C.GL_ALWAYS, 1, 0xFF);
                GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
                GlStateManager._glUseProgram(maskProgram);
                GL45C.glProgramUniform1i(maskProgram, maskLayerLocation, layer);
                draw(indexType, layer);
                GL11C.glStencilFunc(GL11C.GL_EQUAL, 1, 0xFF);
                GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
                for (int i = 0; i < layerPasses.length; i++) drawPass(i, indexType, layer);
                bindTexture(0, depth);
                bindTexture(1, copyColor ? lastColor5 : saved[4]);
                GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, depthFbo);
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(GL11C.GL_ALWAYS);
                GlStateManager._depthMask(true);
                GlStateManager._glUseProgram(depthProgram);
                draw(indexType, layer);
            }
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glStencilFunc(GL11C.GL_ALWAYS, 0, 0xFF);
            for (DeferredCompositePass pass : layerPasses) {
                GL45C.glNamedFramebufferRenderbuffer(pass.flywheel$framebuffer().getId(), GL30C.GL_STENCIL_ATTACHMENT,
                        GL30C.GL_RENDERBUFFER, 0);
            }
            modifiedBackDepth = true;
            for (int i = 0; i < layerPasses.length; i++) {
                GL45C.glProgramUniform1i(layerPasses[i].flywheel$program().getProgramId(), farLocations[i], 0);
            }
        }
        prepareLayer(0, color5, indexType);
        if (!depthEnabled) GlStateManager._disableDepthTest();
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthWrite);
        first.setupState();
    }

    // Oldest first. Bound: pow2 bins 1..64. Pool: demand x1.25 in granules. Both grow at once, shrink after
    // SHRINK_READBACKS.
    private void harvestReadbacks() {
        for (int i = 0; i < READBACK_SLOTS; i++) {
            int slot = (readbackSlot + i) % READBACK_SLOTS;
            long fence = readbackFence[slot];
            if (fence == 0) continue;
            int status = GL32C.glClientWaitSync(fence, 0, 0);
            if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) continue;
            GL32C.glDeleteSync(fence);
            readbackFence[slot] = 0;
            ByteBuffer data = readbackData[slot];
            long demand = Integer.toUnsignedLong(data.getInt(0));
            long granules = (demand + demand / 4 + POOL_GRANULE - 1) / POOL_GRANULE;
            long want = Math.min(maxPoolNodes(targets.getCurrentWidth(), targets.getCurrentHeight()),
                    Math.max(MIN_POOL_NODES, granules * POOL_GRANULE));
            if (want > poolNodes) {
                resizePool(want);
                poolShrinks = 0;
            } else if (want * 2 > poolNodes) {
                poolShrinks = 0;
            } else if (++poolShrinks >= SHRINK_READBACKS) {
                resizePool(want);
                poolShrinks = 0;
            }
            int layers = Math.max(1, data.getInt(8));
            int bin = Math.min(MAX_LAYERS, Integer.highestOneBit(layers * 2 - 1));
            if (bin > layerBound) {
                layerBound = bin;
                shrinkReadbacks = 0;
            } else if (bin < layerBound && ++shrinkReadbacks >= SHRINK_READBACKS) {
                layerBound >>= 1;
                shrinkReadbacks = 0;
            } else if (bin == layerBound) {
                shrinkReadbacks = 0;
            }
            if (layerBound > 1) {
                idleReadbacks = 0;
            } else if (++idleReadbacks == RELEASE_READBACKS) {
                releaseTargets();
                width = height = 0;
            }
        }
    }

    // Contents are rebuilt every frame: no copy.
    private void resizePool(long count) {
        int buffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(buffer, count * NODE_BYTES, 0);
        int old = nodes.getId();
        ((ShaderStorageBufferAccessor) nodes).flywheel$setId(buffer);
        GlStateManager._glDeleteBuffers(old);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, nodes.getIndex(), buffer);
        poolNodes = count;
    }

    private void drawPass(int index, int indexType, int layer) {
        DeferredCompositePass pass = layerPasses[index];
        Set<Integer> alt = pass.flywheel$readAlt();
        for (int buffer : pass.flywheel$mipmaps()) {
            var target = targets.get(buffer);
            IrisRenderSystem.generateMipmaps(alt.contains(buffer) ? target.getAltTexture() : target.getMainTexture(),
                    GL11C.GL_TEXTURE_2D);
            target.turnOnMips(alt.contains(buffer));
        }
        pass.setupState();
        Program p = pass.flywheel$program();
        p.use();
        customUniforms.push(p);
        GL45C.glProgramUniform1i(p.getProgramId(), farLocations[index], 1);
        draw(indexType, layer);

        BlendModeOverride.restore();
    }

    private void prepareLayer(int layer, int color5, int indexType) {
        bindTexture(0, color5);
        bindTexture(1, savedWeather);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, copyFbo);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._colorMask(15);
        for (int i = 0; i < 4; i++) GlStateManager._disableBlend(i);
        GlStateManager._glUseProgram(copyProgram);
        draw(indexType, layer);
        for (int i = 0; i < 8; i++) {
            bindTexture(i, i == 7 ? ((GlTexture) targets.getDepthTextureNoTranslucents()).glId() : saved[i]);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_ALWAYS);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(15);
        for (int i = 0; i < 4; i++) GlStateManager._disableBlend(i);
        GlStateManager._glUseProgram(program);
        GL45C.glProgramUniform1i(program, widthLocation, width);
        GL45C.glProgramUniform1i(program, layerLocation, layer);
        draw(indexType, layer);
    }

    /**
     * Live renderer-owned allocations, excluding the layer pool owned by Iris. Render thread only.
     */
    public long allocatedBytes() {
        return textureBytes + (commands == 0 ? 0 : MAX_LAYERS * 20L + READBACK_SLOTS * 4L);
    }

    private void copy(int from, int to) {
        GL43C.glCopyImageSubData(from, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                to, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    private void releaseTargets() {
        for (int texture : saved) if (texture != 0) GlStateManager._deleteTexture(texture);
        if (fbo != 0) GlStateManager._glDeleteFramebuffers(fbo);
        if (savedWeather != 0) GlStateManager._deleteTexture(savedWeather);
        if (copyFbo != 0) GlStateManager._glDeleteFramebuffers(copyFbo);
        if (depthFbo != 0) GlStateManager._glDeleteFramebuffers(depthFbo);
        if (maskFbo != 0) GlStateManager._glDeleteFramebuffers(maskFbo);
        if (stencil != 0) GL30C.glDeleteRenderbuffers(stencil);
        Arrays.fill(saved, 0);
        fbo = 0;
        savedWeather = 0;
        copyFbo = depthFbo = maskFbo = stencil = 0;
        textureBytes = 0;
    }

    @Override
    public void close() {
        releaseTargets();
        if (program != 0) GlStateManager.glDeleteProgram(program);
        if (copyProgram != 0) GlStateManager.glDeleteProgram(copyProgram);
        if (depthProgram != 0) GlStateManager.glDeleteProgram(depthProgram);
        if (commandsProgram != 0) GlStateManager.glDeleteProgram(commandsProgram);
        if (tilesProgram != 0) GlStateManager.glDeleteProgram(tilesProgram);
        if (maskProgram != 0) GlStateManager.glDeleteProgram(maskProgram);
        if (commands != 0) GlStateManager._glDeleteBuffers(commands);
        for (int i = 0; i < READBACK_SLOTS; i++) {
            if (readbackFence[i] != 0) GL32C.glDeleteSync(readbackFence[i]);
            if (readback[i] != 0) GlStateManager._glDeleteBuffers(readback[i]);
            readbackFence[i] = 0;
            readback[i] = 0;
        }
        program = copyProgram = depthProgram = commandsProgram = tilesProgram = maskProgram = commands = 0;
    }
}
