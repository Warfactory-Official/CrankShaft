package dev.engine_room.flywheel.impl.visualization;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class EntityOutlineSubmits {
    private static final List<EntityRenderState> STATES = new ArrayList<>();

    private EntityOutlineSubmits() {
    }

    public static void clear() {
        STATES.clear();
    }

    public static void record(EntityRenderState state) {
        STATES.add(state);
    }

    public static void submit(EntityRenderDispatcher dispatcher, PoseStack poseStack, LevelRenderState levelRenderState,
                              SubmitNodeCollector output) {
        if (STATES.isEmpty()) {
            return;
        }
        if (!levelRenderState.shouldShowEntityOutlines) {
            STATES.clear();
            return;
        }
        CameraRenderState camera = levelRenderState.cameraRenderState;
        Vec3 cameraPos = camera.pos;
        var collector = new OutlineOnlyCollector(output, 0);
        for (EntityRenderState state : STATES) {
            dispatcher.submit(state, camera, state.x - cameraPos.x(), state.y - cameraPos.y(), state.z - cameraPos.z(),
                    poseStack, collector);
        }
        STATES.clear();
    }

    private static final class OutlineOnlyCollector implements SubmitNodeCollector {
        private final SubmitNodeCollector storage;
        private final SubmitNodeCollection collection;
        private final int order;

        OutlineOnlyCollector(SubmitNodeCollector storage, int order) {
            this.storage = storage;
            this.collection = (SubmitNodeCollection) storage.order(order);
            this.order = order;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return order == this.order ? this : new OutlineOnlyCollector(storage, order);
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                    int lightCoords, int overlayCoords, int tintedColor,
                                    @Nullable TextureAtlasSprite sprite, int outlineColor,
                                    ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
            if (outlineColor == 0) {
                return;
            }
            RenderType outlineType = renderType.isOutline() ? renderType : renderType.outline().orElse(null);
            if (outlineType == null) {
                return;
            }
            collection.submitModel(model, state, poseStack, outlineType, lightCoords, overlayCoords, tintedColor,
                    sprite, outlineColor, null);
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> modelParts,
                                     int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
            if (outlineColor == 0) {
                return;
            }
            RenderType outlineType = renderType.isOutline() ? renderType : renderType.outline().orElse(null);
            if (outlineType == null) {
                return;
            }
            collection.submitBlockModel(poseStack, outlineType, modelParts, tintLayers, lightCoords, overlayCoords,
                    outlineColor);
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                               int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
                               ItemStackRenderState.FoilType foilType) {
            if (outlineColor == 0) {
                return;
            }
            collection.outline.submit(new ItemFeatureRenderer.Submit(poseStack.last().copy(), displayContext, 15728880,
                    OverlayTexture.NO_OVERLAY, outlineColor, ItemStackRenderState.LayerRenderState.EMPTY_TINTS, quads,
                    ItemStackRenderState.FoilType.NONE));
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState,
                                      int outlineColor) {
            if (outlineColor == 0) {
                return;
            }
            collection.outline.submit(
                    new MovingBlockFeatureRenderer.Submit(new Matrix4f(poseStack.last().pose()), movingBlockRenderState,
                            outlineColor));
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                         SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
            if (renderType.isOutline()) {
                collection.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
            }
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        }

        @Override
        public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                                  boolean seeThrough, int lightCoords, CameraRenderState camera) {
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                               Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                               int outlineColor) {
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        }

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
        }

        @Override
        public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                       float width, boolean afterTerrain) {
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        }

        @Override
        public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera,
                                          boolean onTop) {
        }
    }
}
