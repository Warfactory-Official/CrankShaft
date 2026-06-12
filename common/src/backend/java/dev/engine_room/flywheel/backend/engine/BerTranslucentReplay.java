package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture.CapturedDraw;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

import java.util.List;

public final class BerTranslucentReplay {
    private BerTranslucentReplay() {
    }

    public static void replay(RenderPass pass, BerTranslucentCapture capture, OitMode mode, OitFramebuffer framebuffer,
                              GpuTextureView lightmapView, GpuTextureView overlayView, GpuTextureView blueNoiseView,
                              GpuSampler loSampler, GpuSampler oitSampler, GpuSampler noiseSampler) {
        for (BerFamily family : BerFamily.VALUES) {
            List<CapturedDraw> draws = capture.draws(family);
            if (draws.isEmpty()) {
                continue;
            }

            pass.setPipeline(OitPipelines.berProducer(family, mode));

            // The family's vertex samples the lightmap (Sampler2) / overlay (Sampler1) where declared; both are
            // frame-constant. The BER atlas (Sampler0) is per-draw (bound from the captured RenderType below).
            if (family.overlay) {
                pass.bindTexture("Sampler1", overlayView, loSampler);
            }
            if (family.lightmap) {
                pass.bindTexture("Sampler2", lightmapView, loSampler);
            }
            framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);

            for (int i = 0; i < draws.size(); i++) {
                CapturedDraw draw = draws.get(i);
                PreparedRenderType renderType = draw.renderType();
                StagedVertexBuffer.ExecuteInfo info = draw.info();

                // Mirror PreparedRenderType.drawFromBuffer inside the open OIT pass: per-draw transform + the
                // RenderType's textures (the atlas binds Sampler0; a lightmap/overlay in the list re-binds
                // identically to the frame views above).
                pass.setUniform("DynamicTransforms", renderType.dynamicTransforms());
                List<PreparedRenderType.Texture> textures = renderType.textures();
                for (int t = 0; t < textures.size(); t++) {
                    PreparedRenderType.Texture texture = textures.get(t);
                    String name = texture.name();
                    // A family without the vertex overlay/lightmap declares no Sampler1/Sampler2 slot to re-bind.
                    if ((!family.overlay && "Sampler1".equals(name)) || (!family.lightmap && "Sampler2".equals(name))) {
                        continue;
                    }
                    pass.bindTexture(name, texture.textureView(), texture.sampler());
                }
                pass.setVertexBuffer(0, info.vertexBuffer()
                                            .slice());
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        }
    }
}
