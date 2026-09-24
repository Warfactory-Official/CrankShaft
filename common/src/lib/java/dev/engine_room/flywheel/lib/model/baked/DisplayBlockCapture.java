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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
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
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> modelParts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        captured.add(new Captured(poseStack.last().copy(), renderType, List.copyOf(modelParts), tintLayers.clone()));
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor,
                                @Nullable TextureAtlasSprite sprite, int outlineColor,
                                ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
        special = true;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        special = true;
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
        special = true;
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                           int outlineColor) {
        special = true;
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        special = true;
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        special = true;
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState,
                                  int outlineColor) {
        special = true;
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
        special = true;
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
        special = true;
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
                           ItemStackRenderState.FoilType foilType) {
        special = true;
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        special = true;
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        special = true;
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
        special = true;
    }

    /**
     * One {@code submitBlockModel}: the pose includes the model's own transformation.
     */
    public record Captured(PoseStack.Pose pose, RenderType renderType, List<BlockStateModelPart> parts,
                           int[] tints) {
    }
}
