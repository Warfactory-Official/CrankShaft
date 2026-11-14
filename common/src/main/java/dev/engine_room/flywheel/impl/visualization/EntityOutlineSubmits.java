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
                                    #if MC >= 26.3
                                    @Nullable UvMapping sprite, int outlineColor) {
                                    #else
                                    @Nullable TextureAtlasSprite sprite, int outlineColor,
                                    ModelFeatureRenderer.@Nullable CrumblingOverlay _) {
                                    #endif
            if (outlineColor == 0) {
                return;
            }
            RenderType outlineType = renderType.isOutline() ? renderType : renderType.outline().orElse(null);
            if (outlineType == null) {
                return;
            }
            collection.submitModel(model, state, poseStack, outlineType, lightCoords, overlayCoords, tintedColor,
                    sprite, outlineColor #if MC < 26.3 , null #endif);
        }

        #if MC >= 26.3
        @Override
        public <S> void submitCrumblingOverlay(Model<? super S> _, S _, PoseStack _,
                                               RenderType _, int _, int _,
                                               int _, ModelFeatureRenderer.CrumblingOverlay _) {
        }

        @Override
        public void submitTextBackground(PoseStack _, float _, float _, float _, float _,
                                          int _, Font.DisplayMode _, int _) {
        }
        #endif

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
        public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int _,
                               int _, int outlineColor, int[] _,
                               #if MC >= 26.3
                               ItemQuads quads,
                               #else
                               List<BakedQuad> quads,
                               #endif
                               ItemStackRenderState.FoilType _) {
            if (outlineColor == 0) {
                return;
            }
            collection.outline.submit(new ItemFeatureRenderer.Submit(poseStack.last().copy(), displayContext, 15728880,
                    OverlayTexture.NO_OVERLAY, outlineColor, ItemStackRenderState.LayerRenderState.EMPTY_TINTS,
                    #if MC >= 26.3
                    quads.all(),
                    #else
                    quads,
                    #endif
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
                            outlineColor #if MC >= 26.3 , false #endif));
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                         SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
            if (renderType.isOutline()) {
                collection.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
            }
        }

        @Override
        public void submitShadow(PoseStack _, float _, List<EntityRenderState.ShadowPiece> _) {
        }

        @Override
        public void submitNameTag(PoseStack _, @Nullable Vec3 _, int _, Component _,
                                  boolean _, int _, CameraRenderState _) {
        }

        @Override
        public void submitText(PoseStack _, float _, float _, FormattedCharSequence _, boolean _,
                               Font.DisplayMode _, int _, int _, int _,
                               int _) {
        }

        @Override
        public void submitFlame(PoseStack _, EntityRenderState _, Quaternionf _) {
        }

        @Override
        public void submitLeash(PoseStack _, EntityRenderState.LeashState _) {
        }

        @Override
        public void submitBreakingBlockModel(PoseStack _, List<BlockStateModelPart> _, int _
                                             #if MC >= 26.3 , boolean _ #endif) {
        }

        @Override
        public void submitShapeOutline(PoseStack _, VoxelShape _, RenderType _, int _,
                                       float _, boolean _) {
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState _) {
        }

        @Override
        public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group _, CameraRenderState _,
                                          boolean _) {
        }
    }
}
