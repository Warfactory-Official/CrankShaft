package dev.engine_room.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
#if MC >= 26.3
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
#else
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
#endif
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs vanilla's entity-context block draw ({@code BlockModelResolver.update} then {@code BlockModelRenderState.submit})
 * into a capture of the block-model submits it makes. Any other submit comes from a special renderer. Loaders whose
 * renderer API adds a block-model submit overload capture it in a subclass.
 */
public class DisplayBlockCapture implements SubmitNodeCollector {
    private final List<Captured> captured = new ArrayList<>();
    private boolean special;

    /**
     * {@code zOffset}: {@code submitWithZOffset} (item frames). {@code false} when a special renderer draws the block.
     */
    public final boolean run(BlockState state, BlockDisplayContext context, boolean zOffset) {
        BlockModelRenderState renderState = new BlockModelRenderState();
        new BlockModelResolver(Minecraft.getInstance().getModelManager()).update(renderState, state, context);
        if (zOffset) {
            renderState.submitWithZOffset(new PoseStack(), this, 0, OverlayTexture.NO_OVERLAY, 0);
        } else {
            renderState.submit(new PoseStack(), this, 0, OverlayTexture.NO_OVERLAY, 0);
        }
        return !special;
    }

    public final List<Captured> captured() {
        return captured;
    }

    @Override
    public OrderedSubmitNodeCollector order(int _) {
        return this;
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> modelParts,
                                 int[] tintLayers, int _, int _, int _) {
        captured.add(new Captured(poseStack.last().copy(), renderType, List.copyOf(modelParts), tintLayers.clone()));
    }

    @Override
    public <S> void submitModel(Model<? super S> _, S _, PoseStack _, RenderType _,
                                int _, int _, int _,
                                #if MC >= 26.3
                                @Nullable UvMapping _, int _) {
                                #else
                                @Nullable TextureAtlasSprite _, int _,
                                ModelFeatureRenderer.@Nullable CrumblingOverlay _) {
                                #endif
        special = true;
    }

    #if MC >= 26.3
    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> _, S _, PoseStack _,
                                           RenderType _, int _, int _,
                                           int _, ModelFeatureRenderer.CrumblingOverlay _) {
        special = true;
    }

    @Override
    public void submitTextBackground(PoseStack _, float _, float _, float _, float _,
                                      int _, Font.DisplayMode _, int _) {
        special = true;
    }
    #endif

    @Override
    public void submitShadow(PoseStack _, float _, List<EntityRenderState.ShadowPiece> _) {
        special = true;
    }

    @Override
    public void submitNameTag(PoseStack _, @Nullable Vec3 _, int _, Component _,
                              boolean _, int _, CameraRenderState _) {
        special = true;
    }

    @Override
    public void submitText(PoseStack _, float _, float _, FormattedCharSequence _, boolean _,
                           Font.DisplayMode _, int _, int _, int _,
                           int _) {
        special = true;
    }

    @Override
    public void submitFlame(PoseStack _, EntityRenderState _, Quaternionf _) {
        special = true;
    }

    @Override
    public void submitLeash(PoseStack _, EntityRenderState.LeashState _) {
        special = true;
    }

    @Override
    public void submitMovingBlock(PoseStack _, MovingBlockRenderState _,
                                  int _) {
        special = true;
    }

    @Override
    public void submitBreakingBlockModel(PoseStack _, List<BlockStateModelPart> _, int _
                                        #if MC >= 26.3 , boolean _ #endif) {
        special = true;
    }

    @Override
    public void submitShapeOutline(PoseStack _, VoxelShape _, RenderType _, int _,
                                   float _, boolean _) {
        special = true;
    }

    @Override
    public void submitItem(PoseStack _, ItemDisplayContext _, int _,
                           int _, int _, int[] _,
                           #if MC >= 26.3
                           ItemQuads _,
                           #else
                           List<BakedQuad> _,
                           #endif
                           ItemStackRenderState.FoilType _) {
        special = true;
    }

    @Override
    public void submitCustomGeometry(PoseStack _, RenderType _,
                                     SubmitNodeCollector.CustomGeometryRenderer _) {
        special = true;
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState _) {
        special = true;
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group _, CameraRenderState _, boolean _) {
        special = true;
    }

    /**
     * One {@code submitBlockModel}: the pose includes the model's own transformation.
     */
    public record Captured(PoseStack.Pose pose, RenderType renderType, List<BlockStateModelPart> parts,
                           int[] tints) {
    }
}
