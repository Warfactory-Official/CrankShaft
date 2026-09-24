package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.animal.llama.LlamaSpitModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.state.LlamaSpitRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.LlamaSpit;

public final class LlamaSpitVisual extends EntityModelVisual<LlamaSpit, LlamaSpitRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/llama/llama_spit.png");

    @SuppressWarnings("unchecked")
    public LlamaSpitVisual(VisualizationContext ctx, LlamaSpit entity, float partialTick) {
        super(ctx, entity, partialTick, ModelLayers.LLAMA_SPIT,
                root -> (EntityModel<LlamaSpitRenderState>) (EntityModel<?>) new LlamaSpitModel(root));
    }

    @Override
    protected Identifier texture(LlamaSpitRenderState state) {
        return TEXTURE;
    }

    @Override
    protected void setupRootPose(PoseStack pose, LlamaSpitRenderState state) {
        pose.translate(0.0F, 0.15F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(state.yRot - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(state.xRot));
    }
}
