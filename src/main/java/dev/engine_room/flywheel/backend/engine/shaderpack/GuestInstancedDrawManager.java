package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedLight;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.TextureBuffer;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.lib.model.QuadIndexSequence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL40;

import java.util.ArrayList;
import java.util.List;

public final class GuestInstancedDrawManager extends DrawManager<InstancedInstancer<?>> implements GuestRenderer {
    private static final ResourceLocation BLOB_SHADOW = new ResourceLocation("minecraft", "textures/misc/shadow.png");
    private final List<GuestInstancedDraw> draws = new ArrayList<>();
    private final List<GuestInstancedDraw> translucent = new ArrayList<>();
    private final List<RenderChunk> chunks = new ArrayList<>();
    private final MeshPool meshes = new MeshPool(new GuestVertexExtras());
    private final GlVertexArray vao = GlVertexArray.create();
    private final GlVertexArray chunkVao = GlVertexArray.create();
    private final TextureBuffer instances = new TextureBuffer();
    private final InstancedLight light = new InstancedLight();
    private final GuestPrograms programs = new GuestPrograms();
    private final GuestPipelines guestPipelines = new GuestPipelines();
    private final GuestPipelines shadowPipelines = new GuestPipelines();
    private GuestTerrainBatch nativeTerrain;
    private final IndexPool terrainIndices = new IndexPool();
    private GuestPatchIndices patches;
    private boolean patchIndicesPrepared;
    private long shadowFrames;
    public GuestInstancedDrawManager() {
        try {
            meshes.bind(vao);
            terrainIndices.bind(chunkVao);
        } catch (RuntimeException | Error error) {
            meshes.delete();
            terrainIndices.delete();
            vao.delete();
            chunkVao.delete();
            instances.delete();
            light.delete();
            guestPipelines.delete();
            shadowPipelines.delete();
            throw error;
        }
    }

    private static void bindMaterial(Material material) {
        // TODO (ARB_bindless_texture): Put finalized material texture/sampler pairs in dynamically uniform handle tables.
        // Coordinate immutable texture state/residency with the host; fence retirement across atlas and pack reloads.
        MaterialRenderState.setupOit(material);
        GlStateManager.depthMask(material.writeMask().depth());
    }

    static boolean sharedOitMaterial(GuestRenderPass pass, GuestDraw draw) {
        var kind = ((GuestDraw) draw).kind(pass.host);
        if (TaggedEnvironment.isRoutedKind(kind)) return false;
        var transparency = draw.material().transparency();
        return transparency == Transparency.TRANSLUCENT || transparency == Transparency.ORDER_INDEPENDENT;
    }

    static void requireInputs(GuestPrograms.Program program, GuestDraw draw) {
        if (program.needsMidUv && !draw.mesh().hasMidUv()) {
            throw new IllegalStateException(
                    "Shader pack requires sprite centers from a non-quad mesh; implement MidUvMesh");
        }
    }

    static GuestPhase phase(GuestRenderPass pass, GuestDraw draw) {
        var kind = ((GuestDraw) draw).kind(pass.host);
        if (kind == TaggedEnvironment.KIND_ENTITY_EYES) return GuestPhase.SPIDER_EYES;
        if (kind == TaggedEnvironment.KIND_ENTITY_TRANSLUCENT) return GuestPhase.ENTITIES_TRANSLUCENT;
        if (kind == TaggedEnvironment.KIND_ENTITY_BLENDED) return GuestPhase.ENTITIES;
        var transparency = draw.material().transparency();
        if (transparency == Transparency.OPAQUE) {
            if (kind == TaggedEnvironment.KIND_ENTITY) return GuestPhase.ENTITIES;
            if (kind == TaggedEnvironment.KIND_BORROWED_BLOCK) return GuestPhase.TERRAIN_SOLID;
        }
        if (transparency == Transparency.TRANSLUCENT && kind == TaggedEnvironment.KIND_ENTITY) {
            return pass.host.contracts()
                            .has(ContractProgram.GBUFFERS_ENTITIES) ? GuestPhase.ENTITIES : GuestPhase.ENTITIES_TRANSLUCENT;
        }
        return switch (transparency) {
            case OPAQUE -> GuestPhase.BLOCK_ENTITIES;
            case ADDITIVE -> GuestPhase.BEACON_BEAM;
            case LIGHTNING -> GuestPhase.LIGHTNING;
            case GLINT -> GuestPhase.ARMOR_GLINT;
            case CRUMBLING -> GuestPhase.DESTROY;
            case TRANSLUCENT, ORDER_INDEPENDENT -> GuestPhase.TERRAIN_TRANSLUCENT;
        };
    }

    static void drawChunk(RenderChunk chunk, int first, int count, GlVertexArray chunkVao, IndexPool terrainIndices,
                          boolean patches) {
        var minecraft = Minecraft.getMinecraft();
        chunkVao.bindForDraw();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        minecraft.entityRenderer.enableLightmap();
        GlStateManager.enableCull();
        var vbo = chunk.getVertexBufferByLayer(BlockRenderLayer.TRANSLUCENT.ordinal());
        var stride = vbo.vertexFormat.getSize();
        if (stride != 28 && stride != 56) throw new IllegalStateException("Unsupported shader-host chunk stride " + stride);
        var host = GuestHost.current();
        var slice = host.terrainSlice(vbo);
        GlStateManager.pushMatrix();
        if (slice.region()) {
            var origin = chunk.getPosition();
            var container = minecraft.renderGlobal.renderContainer;
            GlStateManager.translate((float) (TerrainBuffer.originCoordinate(origin.getX(), true) - container.viewEntityX),
                    (float) (TerrainBuffer.originCoordinate(origin.getY(), true) - container.viewEntityY),
                    (float) (TerrainBuffer.originCoordinate(origin.getZ(), true) - container.viewEntityZ));
        } else {
            minecraft.renderGlobal.renderContainer.preRenderChunk(chunk);
            chunk.multModelviewMatrix();
        }
        // TODO: Rewrite terrain inputs and reuse GlVertexArray's ARB_vertex_attrib_binding/ARB_direct_state_access paths.
        // Keep the format fixed and update only each chunk's buffer/offset instead of rebuilding pointers per group.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, slice.buffer());
        var offset = slice.offset();
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, stride, offset);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, stride, offset + 12);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, stride, offset + 16);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, stride, offset + 24);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        if (stride == 56) {
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glNormalPointer(GL11.GL_BYTE, stride, offset + 28);
            if (host.optifineLayout()) {
                attribute(11, 3, GL11.GL_SHORT, false, stride, offset + 48);
                attribute(12, 2, GL11.GL_FLOAT, false, stride, offset + 32);
                attribute(13, 4, GL11.GL_SHORT, false, stride, offset + 40);
                GL20.glDisableVertexAttribArray(14);
            } else {
                attribute(11, 4, GL11.GL_SHORT, false, stride, offset + 32);
                attribute(12, 2, GL11.GL_FLOAT, false, stride, offset + 40);
                attribute(13, 4, GL11.GL_BYTE, true, stride, offset + 48);
                attribute(14, 4, GL11.GL_BYTE, false, stride, offset + 52);
            }
        } else {
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            for (var location = 11; location <= 14; location++) GL20.glDisableVertexAttribArray(location);
        }
        if (patches) GL11.glDrawArrays(GL40.GL_PATCHES, first * 4, count * 4);
        else {
            // Geometry stages consume triangles; share the canonical quad triangulation with instance meshes.
            terrainIndices.updateCount(QuadIndexSequence.INSTANCE, (first + count) * 6);
            terrainIndices.flush();
            GL11.glDrawElements(GL11.GL_TRIANGLES, count * 6, GL11.GL_UNSIGNED_INT, (long) first * 6 * Integer.BYTES);
        }
        GlStateManager.popMatrix();
    }

    private static void attribute(int location, int size, int type, boolean normalized, int stride, long offset) {
        GL20.glEnableVertexAttribArray(location);
        GL20.glVertexAttribPointer(location, size, type, normalized, stride, offset);
    }

    @Override
    public boolean supportsRenderIdentity() {
        return true;
    }

    @Override
    protected <I extends Instance> InstancedInstancer<I> create(InstancerKey<I> key) {
        return new InstancedInstancer<>(key, new AbstractInstancer.Recreate<>(key, this));
    }

    @Override
    protected <I extends Instance> void initialize(InstancerKey<I> key, InstancedInstancer<?> instancer) {
        instancer.init();
        var modelMeshes = key.model().meshes();
        for (var index = 0; index < modelMeshes.size(); index++) {
            var entry = modelMeshes.get(index);
            var draw = new GuestInstancedDraw(instancer, meshes.alloc(entry.mesh()),
                    new GroupKey<>(key.type(), key.environment()), entry.material(), key.bias(), index, entry.mesh(),
                    key.model());
            draws.add(draw);
            instancer.addDrawCall(draw);
        }
    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage) {
        // Pack shutdown can precede the client tick that replaces this engine.
        if (!GuestRenderPass.active()) return;
        super.render(lightStorage, environmentStorage);
        instancers.values().removeIf(instancer -> {
            if (instancer.instanceCount() == 0) {
                instancer.delete();
                return true;
            }
            instancer.updateBuffer();
            return false;
        });
        draws.removeIf(InstancedDraw::deleted);
        meshes.flush();
        patchIndicesPrepared = false;
        light.flush(lightStorage);
        Uniforms.bindAll();
        translucent.clear();
        try (var pass = new GuestRenderPass(false, false)) {
            programs.own(pass.host);
            guestPipelines.own(pass.host);
            shadowPipelines.own(pass.host);
            if (pass.shadow && !pass.host.contracts().properties.shadowEnabled()) return;
            if (pass.shadow) shadowFrames++;
            vao.bindForDraw();
            for (var draw : draws) {
                if (pass.host.suppressBlobShadows && draw.material().texture().equals(BLOB_SHADOW)) continue;
                if (pass.shadow && !((GuestDraw) draw).castsShadow(pass.host)) continue;
                var kind = ((GuestDraw) draw).kind(pass.host);
                // 1.12.2: ordinary translucent entity layers precede AUSM's deferred pass, alongside vanilla entities.
                var deferredEntity = !pass.shadow && kind == TaggedEnvironment.KIND_ENTITY
                        && draw.material().transparency() == Transparency.TRANSLUCENT
                        && pass.host.contracts().has(ContractProgram.GBUFFERS_ENTITIES);
                var lateEntity = TaggedEnvironment.isRoutedKind(kind);
                if (draw.material().transparency() != Transparency.OPAQUE && !deferredEntity || lateEntity) {
                    translucent.add(draw);
                    continue;
                }
                pass.activate(phase(pass, draw));
                var program = programs.get(pass, draw, false);
                bindDraw(pass, program, draw, 0);
                program.activateInstances();
                draw.render(instances, program.tessellated ? patches : null, false);
                program.finishInstances();
            }
        }
    }

    @Override
    public void renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage) {
        if (!GuestRenderPass.active()) return;
        Uniforms.bindAll();
        var minecraft = Minecraft.getMinecraft();
        chunks.clear();
        var pooledTerrain = NothiriumTerrain.captured();
        try (var pass = new GuestRenderPass(true, false)) {
            if (pass.shadow && !pass.host.contracts().properties.shadowEnabled()) return;
            if (!pooledTerrain && (!pass.shadow || pass.host.settings().shadowTranslucent())) {
                for (var chunk : minecraft.renderGlobal.renderContainer.renderChunks) {
                    if (chunk.getVertexBufferByLayer(BlockRenderLayer.TRANSLUCENT.ordinal()).count > 0)
                        chunks.add(chunk);
                }
            }
            var hasTerrain = pooledTerrain ? !NothiriumTerrain.slices().isEmpty() : !chunks.isEmpty();
            if (!hasTerrain && translucent.isEmpty()) return;
            if (!pass.shadow && pass.host.deferredReplay()) {
                GlStateManager.depthMask(true);
                submitNativeTranslucent(pass);
                return;
            }
            var guestPipelines = pass.shadow ? shadowPipelines : this.guestPipelines;
            if ((hasTerrain || translucent.stream().anyMatch(draw -> sharedOitMaterial(pass, draw)))
                    && guestPipelines.begin(pass, programs, translucent, hasTerrain)) {
                if (pooledTerrain && hasTerrain) nativeTerrain().prepareNative(NothiriumTerrain.slices(), false,
                        guestPipelines.terrain(pass, OitPass.DEPTH_RANGE, true).tessellated);
                try {
                    for (var mode : OitPass.values()) {
                        if (mode == OitPass.COEFFICIENTS && guestPipelines.contract().ranks().length == 0) continue;
                        guestPipelines.prepare(mode);
                        if (hasTerrain) {
                            var terrain = guestPipelines.terrain(pass, mode, pooledTerrain);
                            terrain.bind(pass, 0);
                            guestPipelines.bind(terrain.program);
                            if (pooledTerrain) nativeTerrain().draw(terrain);
                            else for (var chunk : chunks) {
                                drawChunk(chunk, 0,
                                        chunk.getVertexBufferByLayer(BlockRenderLayer.TRANSLUCENT.ordinal()).count / 4,
                                        terrain.tessellated);
                            }
                        }
                        vao.bindForDraw();
                        for (var draw : translucent) {
                            if (!sharedOitMaterial(pass, draw)) continue;
                            var program = programs.get(pass, draw, mode, guestPipelines.contract());
                            bindDraw(pass, program, draw, 0);
                            GlStateManager.depthMask(false);
                            guestPipelines.bind(program.program);
                            program.activateInstances();
                            draw.render(instances, program.tessellated ? patches : null, false);
                            program.finishInstances();
                        }
                    }
                    guestPipelines.composite();
                } finally {
                    guestPipelines.finish();
                }
                chunks.clear();
                if (pooledTerrain) NothiriumTerrain.slices().clear();
                translucent.removeIf(draw -> sharedOitMaterial(pass, draw));
                if (translucent.isEmpty()) return;
                pass.rebind();
            }
            submitNativeTranslucent(pass);
            if (pass.shadow) return;
            // Plain blended draws also contribute depth after their colour pass, matching the guest renderer's late seam.
            vao.bindForDraw();
            for (var draw : translucent) {
                var material = draw.material();
                var emissive = !TaggedEnvironment.isRoutedKind(((GuestDraw) draw).kind(pass.host))
                        && (material.transparency() == Transparency.ADDITIVE || material.transparency() == Transparency.LIGHTNING);
                if (material.writeMask()
                            .depth() || material.transparency() == Transparency.GLINT || emissive && pass.host.emissiveLight())
                    continue;
                pass.activate(phase(pass, draw));
                var program = programs.get(pass, draw, false);
                bindDraw(pass, program, draw, 0);
                GlStateManager.colorMask(false, false, false, false);
                GlStateManager.depthMask(true);
                program.activateInstances();
                draw.render(instances, program.tessellated ? patches : null, false);
                program.finishInstances();
            }
        }
    }

    private GuestTerrainBatch nativeTerrain() {
        if (nativeTerrain == null) nativeTerrain = new GuestTerrainBatch();
        return nativeTerrain;
    }

    private void submitNativeTranslucent(GuestRenderPass pass) {
        if (NothiriumTerrain.captured() && !NothiriumTerrain.slices().isEmpty()) {
            pass.activate(GuestPhase.TERRAIN_TRANSLUCENT);
            pass.rebind();
            pass.host.prepareMaterial();
            nativeTerrain().drawNative(pass, List.of(), BlockRenderLayer.TRANSLUCENT);
        }

        if (!chunks.isEmpty()) {
            pass.activate(GuestPhase.TERRAIN_TRANSLUCENT);
            pass.rebind();
            pass.host.prepareMaterial();
            pass.primitive(pass.tessEvaluationShader != 0);
            for (var chunk : chunks)
                drawChunk(chunk, 0, chunk.getVertexBufferByLayer(BlockRenderLayer.TRANSLUCENT.ordinal()).count / 4,
                        pass.tessEvaluationShader != 0);
        }
        vao.bindForDraw();
        for (var draw : translucent) {
            pass.activate(phase(pass, draw));
            var program = programs.get(pass, draw, false);
            bindDraw(pass, program, draw, 0);
            program.activateInstances();
            draw.render(instances, program.tessellated ? patches : null, false);
            program.finishInstances();
        }
    }

    private void bindDraw(GuestRenderPass pass, GuestPrograms.Program program, GuestInstancedDraw draw, int instance) {
        requireInputs(program, draw);
        pass.host.prepareMaterial();
        bindMaterial(program.material(draw));
        program.bindDraw(pass, draw, light, instance);
        if (program.tessellated) {
            if (!patchIndicesPrepared) {
                if (patches == null) patches = new GuestPatchIndices();
                patches.prepare(meshes.pooledMeshes());
                patchIndicesPrepared = true;
            }
            vao.setElementBuffer(patches.buffer());
        } else vao.setElementBuffer(meshes.indexBuffer());
        vao.bindForDraw();
    }

    public boolean sharedOitActive() {
        return guestPipelines.framesRendered > 0 && (guestPipelines.completedPasses & 5) == 5;
    }

    public boolean authoredContractActive() {
        return programs.authoredContractActive();
    }

    public boolean shadowContractActive() {
        return shadowFrames > 0;
    }

    public boolean shadowOitActive() {
        return shadowPipelines.framesRendered > 0;
    }

    private void drawChunk(RenderChunk chunk, int first, int count, boolean patches) {
        drawChunk(chunk, first, count, chunkVao, terrainIndices, patches);
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> blocks) {
        if (!GuestRenderPass.active()) return;
        Uniforms.bindAll();
        var byType = doCrumblingSort(blocks,
                handle -> handle instanceof InstancedInstancer<?> instancer ? instancer : null);
        if (byType.isEmpty()) return;
        try (var pass = new GuestRenderPass(false, true)) {
            vao.bindForDraw();
            for (var group : byType.entrySet()) {
                for (var progress : group.getValue().int2ObjectEntrySet()) {
                    var texture = CrumblingTextures.BREAKING_LOCATIONS[progress.getIntKey()];
                    for (var pair : progress.getValue()) {
                        var instancer = pair.getFirst();
                        for (var raw : instancer.draws()) {
                            var draw = (GuestInstancedDraw) raw;
                            var program = programs.get(pass, draw, true);
                            bindDraw(pass, program, draw, pair.getSecond().index);
                            // Keep the material's diffuse mask separate from the repeating crack texture.
                            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                            TextureBinder.bind(texture);
                            program.activateInstances();
                            draw.render(instances, program.tessellated ? patches : null, true);
                            program.finishInstances();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void delete() {
        if (nativeTerrain != null) nativeTerrain.delete();
        instancers.values().forEach(InstancedInstancer::delete);
        super.delete();
        meshes.delete();
        terrainIndices.delete();
        if (patches != null) patches.delete();
        vao.delete();
        chunkVao.delete();
        instances.delete();
        light.delete();
        programs.delete();
        guestPipelines.delete();
        shadowPipelines.delete();
    }

    @Override
    public void triggerFallback() {
        GuestBackends.disable();
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
    }

    @Override
    public MeshPool meshPool() {
        return meshes;
    }
}
