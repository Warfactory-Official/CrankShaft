package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

import static dev.engine_room.flywheel.backend.engine.shaderpack.GuestSamplerUnits.bindTexture;

final class GuestPipelines {
    private final boolean indirectTerrain;
    private final Int2ObjectOpenHashMap<GuestPrograms.Program> terrain = new Int2ObjectOpenHashMap<>();
    private final GlVertexArray fullscreen = GlVertexArray.create();
    private final int[] viewport = new int[4];
    private final Set<String> rejectedMaterials = new HashSet<>();
    public long framesRendered;
    public int completedPasses;
    private GuestOitContract contract;
    private GuestOitTargets targets;
    private GlProgram composite;
    private GlProgram depth;
    private int[] samplers;
    private int sourceProgram;
    private int sourceVertex;
    private int sourceFragment;
    private int sourceGeometry;
    private int sourceControl;
    private int sourceEvaluation;
    private GuestHost owner;
    private int worldFramebuffer;
    private int sourceHeight;
    private boolean scissor;
    private boolean alphaTest;
    private int alphaFunction;
    private float alphaReference;
    GuestPipelines() {
        this(false);
    }
    GuestPipelines(boolean indirectTerrain) {
        this.indirectTerrain = indirectTerrain;
    }

    boolean begin(GuestRenderPass pass, GuestPrograms programs, List<? extends GuestDraw> draws, boolean drawTerrain) {
        completedPasses = 0;
        if (!pass.shadow && pass.host.deferredReplay()) return false;
        own(pass.host);
        if (owner != pass.host || pass.program != sourceProgram || pass.vertexShader != sourceVertex || pass.fragmentShader != sourceFragment
                || pass.geometryShader != sourceGeometry || pass.tessControlShader != sourceControl || pass.tessEvaluationShader != sourceEvaluation) {
            reset();
            owner = pass.host;
            sourceProgram = pass.program;
            sourceVertex = pass.vertexShader;
            sourceFragment = pass.fragmentShader;
            sourceGeometry = pass.geometryShader;
            sourceControl = pass.tessControlShader;
            sourceEvaluation = pass.tessEvaluationShader;
            try {
                if (!GuestOitTargets.SUPPORTED)
                    throw new UnsupportedOperationException("Shared OIT requires framebuffer direct state access");
                if (pass.host.contracts().translucent(pass.shadow) == null) {
                    GuestShaders.checkNativeEffects(pass);
                    pass.host.demote(pass.host.fragment(pass));
                }
                contract = GuestOitContract.capture(pass);
                targets = new GuestOitTargets(contract.ranks(), contract.formats());
                var spec = new GuestShaders.OitSpec(OitPass.EVALUATE, contract, contract.drawBuffers(), pass.shadow);
                composite = GuestPrograms.fullscreen(GuestOitCodegen.compositeFragment(spec));
                depth = GuestPrograms.fullscreen(GuestOitCodegen.depthFragment());
                pass.host.samplers.capture(pass.program);
                samplers = pass.host.samplers.guestSamplers(
                        2 + contract.ranks().length + contract.drawBuffers().length);
                FlwBackend.LOGGER.info("Shader host shared OIT: {} coefficient sets, {} native outputs",
                        contract.ranks().length, contract.drawBuffers().length);
            } catch (UnsupportedOperationException error) {
                reset();
                pass.rebind();
                FlwBackend.LOGGER.warn("Shader host shared OIT unavailable; preserving native sorted replay: {}",
                        error.getMessage());
                return false;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        if (contract == null) return false;
        try {
            if (drawTerrain) terrain(pass, OitPass.DEPTH_RANGE);
            for (var draw : draws) {
                if (GuestInstancedDrawManager.sharedOitMaterial(pass, draw))
                    programs.get(pass, draw, OitPass.DEPTH_RANGE, contract);
            }
        } catch (UnsupportedOperationException error) {
            pass.rebind();
            if (rejectedMaterials.add(error.getMessage())) {
                FlwBackend.LOGGER.warn("Shader host shared OIT material unavailable; preserving sorted replay: {}",
                        error.getMessage());
            }
            return false;
        }
        worldFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        var type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL11.GL_TEXTURE)
            throw new IllegalStateException("Shader host shared OIT needs texture-backed world depth");
        var texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        sourceHeight = GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_HEIGHT);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        alphaFunction = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        alphaReference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        targets.prepare(worldFramebuffer, texture, viewport[0], viewport[1], viewport[2], viewport[3]);
        return true;
    }

    GuestOitContract contract() {
        return contract;
    }

    void own(GuestHost host) {
        if (owner == host) return;
        reset();
        owner = host;
        sourceProgram = sourceVertex = sourceFragment = sourceGeometry = sourceControl = sourceEvaluation = 0;
    }

    void prepare(OitPass mode) {
        var framebuffer = switch (mode) {
            case DEPTH_RANGE -> targets.depthRangeFbo();
            case COEFFICIENTS -> targets.coefficientsFbo();
            case EVALUATE -> targets.accumulateFbo();
        };
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, viewport[2], viewport[3]);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        GL20.glBlendEquationSeparate(mode == OitPass.DEPTH_RANGE ? GL14.GL_MAX : GL14.GL_FUNC_ADD,
                mode == OitPass.DEPTH_RANGE ? GL14.GL_MAX : GL14.GL_FUNC_ADD);
        for (var slot = 0; slot < targets.attachmentCount(mode); slot++) {
            GL30.glEnablei(GL11.GL_BLEND, slot);
            GL40.glBlendFuncSeparatei(slot, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
            GL40.glBlendEquationSeparatei(slot, mode == OitPass.DEPTH_RANGE ? GL14.GL_MAX : GL14.GL_FUNC_ADD,
                    mode == OitPass.DEPTH_RANGE ? GL14.GL_MAX : GL14.GL_FUNC_ADD);
        }
        completedPasses |= 1 << mode.ordinal();
    }

    GuestPrograms.Program terrain(GuestRenderPass pass, OitPass mode) {
        return terrain(pass, mode, indirectTerrain);
    }

    GuestPrograms.Program terrain(GuestRenderPass pass, OitPass mode, boolean pooled) {
        var buffers = pass.host.drawBuffers(pass.role());
        var authored = pass.host.contracts().translucent(pass.shadow);
        if (authored != null) buffers = authored.drawBuffers;
        var drawBuffers = buffers;
        var key = mode.ordinal() * 2 + (pooled ? 1 : 0);
        var program = terrain.get(key);
        if (program == null) {
            program = GuestPrograms.terrain(pass, new GuestShaders.OitSpec(mode, contract, drawBuffers, pass.shadow), pooled);
            terrain.put(key, program);
        }
        return program;
    }

    void bind(GlProgram program) {
        // TODO (ARB_multi_bind): Batch contiguous guest texture units while preserving the host's per-unit cache.
        program.setVec2("_flw_oitViewportOrigin", viewport[0], viewport[1]);
        program.setVec2("_flw_oitViewportSize", viewport[2], viewport[3]);
        program.setFloat("_flw_oitSourceHeight", sourceHeight);
        program.setInt("_flw_depthRange", samplers[0]);
        program.setInt("_flw_blueNoise", samplers[1]);
        var active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        bindTexture(samplers[0], GL11.GL_TEXTURE_2D, targets.depthRange());
        bindTexture(samplers[1], GL11.GL_TEXTURE_2D, NoiseTextures.BLUE_NOISE.getGlTextureId());
        for (var set = 0; set < contract.ranks().length; set++) {
            program.setInt("_flw_coefficients" + set, samplers[2 + set]);
            bindTexture(samplers[2 + set], GL30.GL_TEXTURE_2D_ARRAY, targets.coefficients(set));
        }
        for (var slot = 0; slot < contract.drawBuffers().length; slot++) {
            var unit = samplers[2 + contract.ranks().length + slot];
            program.setInt("_flw_accumulate" + slot, unit);
            bindTexture(unit, GL11.GL_TEXTURE_2D, targets.accumulate(slot));
        }
        GlStateTracker.hostActiveTexture(active);
    }

    void composite() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, worldFramebuffer);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        restoreBlend();
        GlStateManager.depthMask(false);
        GlStateManager.depthFunc(GL11.GL_ALWAYS);
        GlStateManager.disableCull();
        fullscreen.bindForDraw();
        composite.bind();
        bind(composite);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        depth.bind();
        bind(depth);
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.depthMask(true);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        framesRendered++;
    }

    private void restoreBlend() {
        if (contract.blending(0)) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(contract.blend(0, 0), contract.blend(0, 1),
                contract.blend(0, 2), contract.blend(0, 3));
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        for (var slot = 0; slot < contract.drawBuffers().length; slot++) {
            if (contract.blending(slot)) GL30.glEnablei(GL11.GL_BLEND, slot);
            else GL30.glDisablei(GL11.GL_BLEND, slot);
            GL40.glBlendFuncSeparatei(slot, contract.blend(slot, 0), contract.blend(slot, 1),
                    contract.blend(slot, 2), contract.blend(slot, 3));
        }
    }

    void finish() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, worldFramebuffer);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        restoreBlend();
        if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.alphaFunc(alphaFunction, alphaReference);
        if (alphaTest) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
    }

    private void reset() {
        terrain.values().forEach(program -> program.program.delete());
        terrain.clear();
        rejectedMaterials.clear();
        if (targets != null) targets.delete();
        if (composite != null) composite.delete();
        if (depth != null) depth.delete();
        contract = null;
        targets = null;
        composite = null;
        depth = null;
        framesRendered = 0;
    }

    void delete() {
        reset();
        fullscreen.delete();
    }
}
