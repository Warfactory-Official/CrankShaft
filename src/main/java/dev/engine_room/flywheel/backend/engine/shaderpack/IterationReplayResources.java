package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

final class IterationReplayResources implements DeferredReplayResources {
    private static final int FIRST_TEXTURE = 10;
    private final DeferredReplayTargets targets;
    private final DeferredReplayPass first;
    private final DeferredReplayPass last;
    private final DeferredReplayPass refraction;
    private final DeferredReplayPass diffuseLast;
    private final boolean[] canonicalAlt = new boolean[4];
    private final int[] custom = new int[3];
    private final int[] saved = new int[10];
    private final int[] inputs = new int[6];
    private final int[] historyTargets = new int[8];
    private DeferredReplayQuad quad;
    private int[] banks = new int[0];
    private int[] bankFrames = new int[0];
    private int width, height, waterHeight, depth, opaqueDepth;
    private int materialize, backdrop, advance, historyDepth, historyLoad, resolve, diffuseSave;
    private int fastMaterialize, lightingInputs;
    private int layerCopy;
    private int materialFbo, backdropFbo, advanceFbo, resolveFbo, diffuseFbo;
    private int fastFbo, lightingFbo;
    private int copyFbo;
    private int diffuseBase, varianceBase, knownLayers;
    private boolean fullHistory;
    private int layerNodes;
    private int accumulated, background, backgroundDepth, savedOpaque;
    private int initialLighting;
    private int resolved, frame;
    private int[] kernels, layerLocations;
    private int historyModeLocation, historyClearLocation, historyMaskLocation;
    private long textureBytes;
    private boolean borrowed;
    private int replayCommandBuffer, replayIndexType;
    private long resourceComputeOffset, drawCommandOffset;

    private IterationReplayResources(
            DeferredReplayTargets pipeline,
            DeferredReplayPass first,
            DeferredReplayPass last,
            DeferredReplayPass refraction,
            List<?> passes) {
        targets = pipeline;
        this.first = first;
        this.last = last;
        this.refraction = refraction;
        diffuseLast =
                passes.stream()
                      .map(DeferredReplayPass.class::cast)
                      .filter(pass -> pass.name().equals("composite19"))
                      .findFirst()
                      .orElseThrow();
        var finalRead = ((DeferredReplayPass) passes.getLast()).readAlt();
        for (int i = 0; i < 4; i++) canonicalAlt[i] = finalRead.contains(9 + i);

    }

    static DeferredReplayPlan plan(DeferredReplayTargets pipeline, List<?> passes) {
        var stages = new ArrayList<DeferredReplayPlan.Stage>();
        DeferredReplayPass after = null, refraction = null;
        for (Object entry : passes) {
            DeferredReplayPass pass = (DeferredReplayPass) entry;
            String name = pass.name();
            if (!name.matches("composite[0-9]+")) continue;
            int number = Integer.parseInt(name.substring(9));
            if (number >= 5 && number <= 50) stages.add(new DeferredReplayPlan.Stage(pass, false));
            if (number == 40) refraction = pass;
            if (number == 51) after = pass;
        }
        if (stages.isEmpty()) return null;
        if (!stages.getFirst().pass().name().equals("composite5")
                || refraction == null
                || after == null)
            throw new IllegalStateException("Iteration replay stages changed");
        return new DeferredReplayPlan(
                stages.toArray(DeferredReplayPlan.Stage[]::new),
                after,
                new DeferredReplayPlan.Storage(2, 3, 4, 7, 16, 1 << 16, 1 << 18, 16, 4),
                5,
                new IterationReplayResources(
                        pipeline,
                        stages.getFirst().pass(),
                        stages.getLast().pass(),
                        refraction,
                        passes));
    }

    private static void libraries(Compilation compilation) {
        compilation.appendComponent(
                new Library("iteration_layers", DeferredReplayShaders.resource("iteration_layers.glsl")));
        compilation.appendComponent(
                new Library("iteration_materials", DeferredReplayShaders.resource("iteration_materials.glsl")));
    }

    private static void uniform(int program, String name, int value) {
        GL45C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, name), value);
    }

    private static void bind(int unit, int texture) {
        GlStateTracker.hostActiveTexture(GL20C.GL_TEXTURE0 + FIRST_TEXTURE + unit);
        GlStateManager.bindTexture(texture);
        GL33C.glBindSampler(FIRST_TEXTURE + unit, 0);
    }

    private static int format(int texture) {
        return GL45C.glGetTextureLevelParameteri(texture, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
    }

    private static void barrier() {
        GL43C.glMemoryBarrier(
                GL43C.GL_SHADER_STORAGE_BARRIER_BIT
                        | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                        | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT
                        | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
    }

    private static void complete(int fbo) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Iteration replay framebuffer: " + status);
    }

    @Override
    public int width() {
        return targets.width();
    }

    @Override
    public int height() {
        return targets.height();
    }

    @Override
    public boolean preservesViewport() {
        return true;
    }

    @Override
    public int computeGroupSize() {
        return 8;
    }

    @Override
    public void replayCommands(int buffer, long computeOffset) {
        replayCommandBuffer = buffer;
        resourceComputeOffset = computeOffset;
    }

    @Override
    public void observedCapture(int layers, int metadata) {
        knownLayers = layers;
        fullHistory = metadata < 0;
    }

    private void compile() {
        if (materialize != 0) return;
        quad = new DeferredReplayQuad();
        materialize =
                DeferredReplayShaders.graphics(
                        "iteration_materialize.frag", IterationReplayResources::libraries).handle();
        fastMaterialize =
                DeferredReplayShaders.graphics(
                        "iteration_materialize.frag",
                        ctx -> {
                            libraries(ctx);
                            ctx.define("FLW_FAST_MATERIALIZE");
                        }).handle();
        lightingInputs =
                DeferredReplayShaders.graphics(
                        "iteration_lighting_inputs.frag", IterationReplayResources::libraries).handle();
        uniform(lightingInputs, "lighting", FIRST_TEXTURE);
        uniform(lightingInputs, "weights", FIRST_TEXTURE + 1);
        backdrop =
                DeferredReplayShaders.graphics(
                        "iteration_backdrop.frag", IterationReplayResources::libraries).handle();
        advance =
                DeferredReplayShaders.graphics(
                        "iteration_advance.frag", IterationReplayResources::libraries).handle();
        historyDepth =
                DeferredReplayShaders.compute(
                        "iteration_history.comp", IterationReplayResources::libraries).handle();
        historyLoad =
                DeferredReplayShaders.compute(
                        "iteration_history_load.comp", IterationReplayResources::libraries).handle();
        resolve =
                DeferredReplayShaders.graphics(
                        "iteration_resolve.frag", IterationReplayResources::libraries).handle();
        uniform(resolve, "accumulated", FIRST_TEXTURE);
        diffuseSave =
                DeferredReplayShaders.graphics(
                        "iteration_diffuse_base.frag", IterationReplayResources::libraries).handle();
        uniform(diffuseSave, "lighting", FIRST_TEXTURE);
        uniform(diffuseSave, "weights", FIRST_TEXTURE + 1);
        layerCopy = DeferredReplayShaders.graphics("iteration_layer_copy.frag").handle();
        uniform(layerCopy, "accumulated", FIRST_TEXTURE);
        uniform(layerCopy, "backDepth", FIRST_TEXTURE + 1);
        uniform(layerCopy, "savedOpaque", FIRST_TEXTURE + 2);
        uniform(layerCopy, "savedWater", FIRST_TEXTURE + 3);
        uniform(layerCopy, "savedPrevious", FIRST_TEXTURE + 4);
        for (int i = 0; i < 6; i++) uniform(materialize, "saved" + i, FIRST_TEXTURE + i);
        uniform(materialize, "savedDepth", FIRST_TEXTURE + 6);
        uniform(materialize, "behind", FIRST_TEXTURE + 7);
        uniform(materialize, "behindDepth", FIRST_TEXTURE + 8);
        uniform(materialize, "initialLighting", FIRST_TEXTURE + 9);
        uniform(materialize, "baseLighting", FIRST_TEXTURE + 10);
        for (int i = 0; i < 6; i++) uniform(fastMaterialize, "saved" + i, FIRST_TEXTURE + i);
        uniform(fastMaterialize, "savedDepth", FIRST_TEXTURE + 6);
        uniform(fastMaterialize, "behindDepth", FIRST_TEXTURE + 8);
        uniform(backdrop, "behind", FIRST_TEXTURE);
        uniform(advance, "resolved", FIRST_TEXTURE);
        uniform(advance, "behind", FIRST_TEXTURE + 1);
        uniform(advance, "frontDepth", FIRST_TEXTURE + 2);
        uniform(advance, "backDepth", FIRST_TEXTURE + 3);
        uniform(historyDepth, "depth", FIRST_TEXTURE);
        for (int i = 0; i < 8; i++) uniform(historyLoad, "history[" + i + "]", FIRST_TEXTURE + i);
        kernels =
                new int[]{
                        materialize,
                        backdrop,
                        advance,
                        historyDepth,
                        historyLoad,
                        resolve,
                        diffuseSave,
                        fastMaterialize,
                        lightingInputs,
                        layerCopy
                };
        layerLocations = new int[kernels.length];
        for (int i = 0; i < kernels.length; i++) {
            layerLocations[i] = GL20C.glGetUniformLocation(kernels[i], "layer");
            uniform(kernels[i], "flw_oitActive", 1);
        }
        historyModeLocation = GL20C.glGetUniformLocation(historyDepth, "loadHistory");
        historyClearLocation = GL20C.glGetUniformLocation(historyLoad, "clearHistory");
        historyMaskLocation = GL20C.glGetUniformLocation(historyLoad, "historyMask");
    }

    private int texture(int buffer, boolean alt) {
        return alt ? targets.get(buffer).alt() : targets.get(buffer).main();
    }

    private int read(DeferredReplayPass pass, int buffer) {
        return texture(buffer, pass.readAlt().contains(buffer));
    }

    @Override
    public void snapshot() {
        compile();
        int w = width(), h = height();
        int currentDepth = targets.depth();
        boolean resized = w != width || h != height || currentDepth != depth;
        String[] imageNames = {"depthtexS", "waterDepth2D", "prevDepth2D"};
        for (int i = 0; i < 3; i++) custom[i] = targets.image(imageNames[i]);
        if (resized) {
            release();
            width = w;
            height = h;
            depth = currentDepth;
            for (int kernel : kernels) {
                uniform(kernel, "width", w);
                uniform(kernel, "height", h);
            }
            opaqueDepth = targets.opaqueDepth();
            waterHeight = GL45C.glGetTextureLevelParameteri(custom[1], 0, GL11C.GL_TEXTURE_HEIGHT);
            for (int i = 0; i < 6; i++)
                saved[i] = allocate(targets.get(i).format(), h);
            saved[6] = allocate(GuestOitTargets.sizedDepthFormat(depth), h);
            for (int i = 0; i < 3; i++)
                saved[7 + i] = allocate(format(custom[i]), i == 1 ? waterHeight : h);
            savedOpaque = allocate(GuestOitTargets.sizedDepthFormat(opaqueDepth), h);
            backgroundDepth = allocate(GuestOitTargets.sizedDepthFormat(opaqueDepth), h);
            accumulated = allocate(GL30C.GL_RGBA16F, h);
            background = allocate(GL30C.GL_RGBA16F, h);
            copyFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT0, background, 0);
            GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT1, custom[0], 0);
            GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT2, custom[2], 0);
            GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_DEPTH_ATTACHMENT, backgroundDepth, 0);
            GL45C.glNamedFramebufferDrawBuffers(copyFbo, new int[]{
                    GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1, GL30C.GL_COLOR_ATTACHMENT2});
            initialLighting = allocate(GL30C.GL_RGBA16F, h);
            diffuseBase = allocate(GL30C.GL_RGBA16F, h);
            varianceBase = allocate(GL11C.GL_RGBA8, h);
            diffuseFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(diffuseFbo, GL30C.GL_COLOR_ATTACHMENT0, diffuseBase, 0);
            GL45C.glNamedFramebufferTexture(
                    diffuseFbo, GL30C.GL_COLOR_ATTACHMENT1, varianceBase, 0);
            GL45C.glNamedFramebufferDrawBuffers(
                    diffuseFbo, new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
            materialFbo = GL45C.glCreateFramebuffers();
            fastFbo = GL45C.glCreateFramebuffers();
            lightingFbo = GL45C.glCreateFramebuffers();
            layerNodes = allocate(GL30C.GL_R32UI, h);
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_COLOR_ATTACHMENT6, layerNodes, 0);
            backdropFbo = GL45C.glCreateFramebuffers();
            advanceFbo = GL45C.glCreateFramebuffers();
            resolveFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffers(
                    materialFbo,
                    new int[]{
                            GL30C.GL_COLOR_ATTACHMENT0,
                            GL30C.GL_COLOR_ATTACHMENT1,
                            GL30C.GL_COLOR_ATTACHMENT2,
                            GL30C.GL_COLOR_ATTACHMENT3,
                            GL30C.GL_COLOR_ATTACHMENT4,
                            GL30C.GL_COLOR_ATTACHMENT5,
                            GL30C.GL_COLOR_ATTACHMENT6,
                            GL30C.GL_COLOR_ATTACHMENT7
                    });
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
            GL45C.glNamedFramebufferTexture(fastFbo, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
            GL45C.glNamedFramebufferTexture(fastFbo, GL30C.GL_COLOR_ATTACHMENT3, layerNodes, 0);
            GL45C.glNamedFramebufferDrawBuffers(
                    fastFbo,
                    new int[]{
                            GL30C.GL_COLOR_ATTACHMENT0,
                            GL30C.GL_COLOR_ATTACHMENT1,
                            GL30C.GL_COLOR_ATTACHMENT2,
                            GL30C.GL_COLOR_ATTACHMENT3
                    });
            GL45C.glNamedFramebufferDrawBuffers(
                    lightingFbo,
                    new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
            GL45C.glNamedFramebufferTexture(advanceFbo, GL30C.GL_COLOR_ATTACHMENT0, accumulated, 0);
            GL45C.glNamedFramebufferTexture(advanceFbo, GL30C.GL_DEPTH_ATTACHMENT, opaqueDepth, 0);
            resolved = 0;
        }
        barrier();
        for (int i = 0; i < 6; i++) {
            inputs[i] = read(first, i);
            copy(inputs[i], saved[i]);
        }
        copy(depth, saved[6]);
        copy(opaqueDepth, savedOpaque);
        for (int i = 0; i < 3; i++) copy(custom[i], saved[7 + i], i == 1 ? waterHeight : height);
        for (int i = 0; i < 8; i++) historyTargets[i] = texture(9 + i / 2, (i & 1) != 0);
        copy(read(first, 6), initialLighting);
        resolved = read(last, 9);
        {
            for (int i = 0; i < 6; i++)
                GL45C.glNamedFramebufferTexture(
                        materialFbo, GL30C.GL_COLOR_ATTACHMENT0 + i, inputs[i], 0);
            for (int i = 0; i < 3; i++)
                GL45C.glNamedFramebufferTexture(
                        fastFbo, GL30C.GL_COLOR_ATTACHMENT0 + i, inputs[i + 3], 0);
            GL45C.glNamedFramebufferTexture(
                    lightingFbo,
                    GL30C.GL_COLOR_ATTACHMENT0,
                    texture(6, !first.readAlt().contains(6)),
                    0);
            GL45C.glNamedFramebufferTexture(
                    lightingFbo,
                    GL30C.GL_COLOR_ATTACHMENT1,
                    texture(0, !first.readAlt().contains(0)),
                    0);
            GL45C.glNamedFramebufferTexture(
                    materialFbo, GL30C.GL_COLOR_ATTACHMENT7, read(first, 6), 0);
            GL45C.glNamedFramebufferTexture(resolveFbo, GL30C.GL_COLOR_ATTACHMENT0, resolved, 0);
            GL45C.glNamedFramebufferTexture(
                    backdropFbo, GL30C.GL_COLOR_ATTACHMENT0, read(refraction, 6), 0);
            complete(materialFbo);
            complete(backdropFbo);
            complete(advanceFbo);
            complete(diffuseFbo);
            complete(fastFbo);
            complete(lightingFbo);
            complete(copyFbo);
        }
        frame = targets.frame();
        borrowed = true;
    }

    @Override
    public void materialize(int layer, int type, long offset) {
        replayIndexType = type;
        drawCommandOffset = offset;
        bank(layer);
        boolean haveHistory = banks[layer * 9] != 0;
        barrier();
        bind(0, accumulated);
        bind(1, opaqueDepth);
        bind(2, saved[7]);
        bind(3, saved[8]);
        bind(4, saved[9]);
        GL42C.glBindImageTexture(1, custom[1], 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32UI);
        state(copyFbo, layerCopy, layer, true);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        barrier();
        int mask = 0;
        for (int i = 0; i < 8; i++) {
            int stored = banks[layer * 9 + i];
            if (stored != 0) mask |= 1 << i;
            bind(i, stored != 0 ? stored : historyTargets[i]);
            GL42C.glBindImageTexture(
                    i,
                    historyTargets[i],
                    0,
                    false,
                    0,
                    GL15C.GL_WRITE_ONLY,
                    i < 6 ? GL30C.GL_RGBA16F : GL30C.GL_RG16);
        }
        use(historyLoad, layer);
        GL45C.glProgramUniform1i(historyLoad, historyMaskLocation, mask);
        GL45C.glProgramUniform1i(
                historyLoad,
                historyClearLocation,
                haveHistory && bankFrames[layer] == frame - 1 ? 0 : 1);
        GL43C.glDispatchComputeIndirect(offset + resourceComputeOffset);
        bind(0, haveHistory ? banks[layer * 9 + 8] : custom[2]);
        GL42C.glBindImageTexture(0, custom[2], 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
        use(historyDepth, layer);
        GL45C.glProgramUniform1i(
                historyDepth, historyModeLocation, bankFrames[layer] == frame - 1 ? 1 : 2);
        GL43C.glDispatchComputeIndirect(offset + resourceComputeOffset);
        barrier();
        for (int i = 0; i < 7; i++) bind(i, saved[i]);
        bind(7, background);
        bind(8, opaqueDepth);
        bind(9, initialLighting);
        bind(10, diffuseBase);
        GL42C.glBindImageTexture(0, custom[0], 0, false, 0, GL15C.GL_READ_WRITE, GL30C.GL_R32UI);
        GL42C.glBindImageTexture(1, custom[1], 0, false, 0, GL15C.GL_READ_WRITE, GL30C.GL_R32UI);
        state(materialFbo, materialize, layer, true);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        state(fastFbo, fastMaterialize, layer, true);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        barrier();
        bind(0, read(first, 6));
        bind(1, varianceBase);
        state(lightingFbo, lightingInputs, layer, false);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
    }

    @Override
    public void beforePass(DeferredReplayPass pass, int layer) {
        if (pass != refraction) return;
        bind(0, background);
        state(backdropFbo, backdrop, layer, false);
        draw(layer);
    }

    @Override
    public void bindGraphicsResources(DeferredReplayPass pass, int layer) {
        GL42C.glBindImageTexture(7, layerNodes, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32UI);
    }

    @Override
    public void advance(int layer, int type, long offset) {
        barrier();
        bind(0, resolved);
        bind(1, background);
        bind(2, depth);
        bind(3, backgroundDepth);
        state(advanceFbo, advance, layer, true);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        barrier();
        mergeLayer(layer);
        saveHistory(layer);
    }

    @Override
    public void afterPass(DeferredReplayPass pass, int layer) {
        if (pass == diffuseLast) {
            barrier();
            bind(0, pass.outputTexture(0));
            bind(1, read(pass, 0));
            state(diffuseFbo, diffuseSave, layer, false);
            draw(layer);
        }
        if (pass != last || layer != 0) return;
        barrier();
        bind(0, resolved);
        bind(1, background);
        bind(2, depth);
        bind(3, backgroundDepth);
        state(advanceFbo, advance, 0, true);
        draw(0);
        barrier();
        // The background pass already shaded pixels without a material layer.
        mergeLayer(0);
        saveHistory(0);
    }

    private void mergeLayer(int layer) {
        bind(0, accumulated);
        state(resolveFbo, resolve, layer, false);
        draw(layer);
    }

    private void saveHistory(int layer) {
        if (banks[layer * 9] == 0) return;
        int previousDispatch = 0;
        if (layer == 0) {
            previousDispatch = GL11C.glGetInteger(GL43C.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
            GL15C.glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, replayCommandBuffer);
        }
        try {
            barrier();
            int mask = 0;
            for (int i = 0; i < 8; i++) {
                int source = canonicalAlt[i / 2] ? historyTargets[i | 1] : historyTargets[i];
                bind(i, source);
                int stored = banks[layer * 9 + i];
                if (stored != 0) mask |= 1 << i;
                GL42C.glBindImageTexture(
                        i,
                        stored,
                        0,
                        false,
                        0,
                        GL15C.GL_WRITE_ONLY,
                        i < 6 ? GL30C.GL_RGBA16F : GL30C.GL_RG16);
            }
            use(historyLoad, layer);
            GL45C.glProgramUniform1i(historyLoad, historyClearLocation, -1);
            GL45C.glProgramUniform1i(historyLoad, historyMaskLocation, mask);
            GL43C.glDispatchComputeIndirect(drawCommandOffset + resourceComputeOffset);
            bind(0, custom[2]);
            GL42C.glBindImageTexture(
                    0, banks[layer * 9 + 8], 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
            use(historyDepth, layer);
            GL45C.glProgramUniform1i(historyDepth, historyModeLocation, 0);
            GL43C.glDispatchComputeIndirect(drawCommandOffset + resourceComputeOffset);
            bankFrames[layer] = frame;
            barrier();
        } finally {
            if (layer == 0) GL15C.glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, previousDispatch);
        }
    }

    private void bank(int layer) {
        if (layer >= bankFrames.length) {
            int old = bankFrames.length;
            bankFrames = Arrays.copyOf(bankFrames, layer + 1);
            Arrays.fill(bankFrames, old, layer + 1, Integer.MIN_VALUE);
            banks = Arrays.copyOf(banks, (layer + 1) * 9);
        }
        if (layer >= knownLayers) return;
        for (int i = 0; i < 9; i++) {
            if (banks[layer * 9 + i] != 0) continue;
            if (i >= 2 && i < 8 && !fullHistory && layer != knownLayers - 1) continue;
            if (i < 8 && (i & 1) != 0 && canonicalAlt[i / 2]) {
                banks[layer * 9 + i] = banks[layer * 9 + i - 1];
                continue;
            }
            int f = i == 8 ? GL30C.GL_R32F : i < 6 ? GL30C.GL_RGBA16F : GL30C.GL_RG16;
            int t = banks[layer * 9 + i] = allocate(f, height);
            GL44C.glClearTexImage(
                    t,
                    0,
                    i == 8 ? GL11C.GL_RED : i < 6 ? GL11C.GL_RGBA : GL30C.GL_RG,
                    GL11C.GL_FLOAT,
                    new float[]{i == 8 ? 1 : 0, 0, 0, 0});
        }
    }

    private void state(int fbo, int program, int layer, boolean writeDepth) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        if (writeDepth) GlStateManager.enableDepth();
        else GlStateManager.disableDepth();
        GlStateManager.depthFunc(GL11C.GL_ALWAYS);
        GlStateManager.depthMask(writeDepth);
        GlStateManager.colorMask(true, true, true, true);
        for (int i = 0; i < 8; i++) GL30C.glDisablei(GL11C.GL_BLEND, i);
        use(program, layer);
    }

    private void use(int program, int layer) {
        GlProgram.bind(program);
        for (int i = 0; i < kernels.length; i++)
            if (kernels[i] == program) {
                GL45C.glProgramUniform1i(program, layerLocations[i], layer);
                break;
            }
    }

    private void draw(int layer) {
        if (layer != 0) {
            GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, replayIndexType, drawCommandOffset);
            return;
        }
        quad.bind();
        GL11C.glDrawElements(GL11C.GL_TRIANGLES, 6, GL11C.GL_UNSIGNED_INT, 0L);
    }

    private int allocate(int format, int h) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, format, width, h);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        textureBytes +=
                (long) width
                        * h
                        * (format == GL30C.GL_RGBA16F || format == GL30C.GL_RGBA16 ? 8 : 4);
        return texture;
    }

    private void copy(int from, int to) {
        copy(from, to, height);
    }

    private void copy(int from, int to, int h) {
        GL43C.glCopyImageSubData(
                from,
                GL11C.GL_TEXTURE_2D,
                0,
                0,
                0,
                0,
                to,
                GL11C.GL_TEXTURE_2D,
                0,
                0,
                0,
                0,
                width,
                h,
                1);
    }

    @Override
    public void restore() {
        if (borrowed) {
            barrier();
            copy(savedOpaque, opaqueDepth);
        }
        borrowed = false;
    }

    @Override
    public long allocatedBytes() {
        return textureBytes;
    }

    @Override
    public void release() {
        for (int t : saved) if (t != 0) GlStateManager.deleteTexture(t);
        for (int i = 0; i < banks.length; i++)
            if (banks[i] != 0 && (i == 0 || banks[i] != banks[i - 1]))
                GlStateManager.deleteTexture(banks[i]);
        for (int t : new int[]{accumulated, background, backgroundDepth, savedOpaque, diffuseBase, varianceBase,
                layerNodes, initialLighting}) {
            if (t != 0) GlStateManager.deleteTexture(t);
        }
        for (int f : new int[]{materialFbo, backdropFbo, advanceFbo, resolveFbo, diffuseFbo, fastFbo, lightingFbo,
                copyFbo}) {
            if (f != 0) GL30C.glDeleteFramebuffers(f);
        }
        Arrays.fill(saved, 0);
        banks = new int[0];
        bankFrames = new int[0];
        accumulated = background = backgroundDepth = savedOpaque = diffuseBase = varianceBase = layerNodes = 0;
        initialLighting = 0;
        materialFbo = backdropFbo = advanceFbo = resolveFbo = diffuseFbo = fastFbo = lightingFbo = copyFbo = 0;
        width = height = 0;
        textureBytes = 0;
    }

    @Override
    public void close() {
        restore();
        release();
        for (int p : new int[]{materialize, backdrop, advance, historyDepth, historyLoad, resolve, diffuseSave,
                fastMaterialize, lightingInputs, layerCopy}) {
            if (p != 0) GL20C.glDeleteProgram(p);
        }
        materialize = backdrop = advance = historyDepth = historyLoad = resolve = diffuseSave = 0;
        fastMaterialize = lightingInputs = layerCopy = 0;
        if (quad != null) {
            quad.delete();
            quad = null;
        }
    }

    private record Library(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
