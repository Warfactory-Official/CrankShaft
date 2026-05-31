package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import dev.engine_room.flywheel.lib.visual.LlamaEntityModel;
import net.minecraft.entity.passive.EntityLlama;
import org.joml.Matrix4fc;

/**
 * The llama's carpet decor: a 0.5-inflated {@code ModelLlama} re-instanced over the body and posed one-to-one off
 * it (vanilla {@code LayerLlamaDecor} — {@code setModelAttributes} + render with the decor texture), shown only
 * while {@code hasColor()}. The 16 dyed carpets share one atlas (per-instance UV by {@code getColor}), so the
 * decor batches into one instancer per bone. The two chest boxes track the body's {@code hasChest} toggle.
 */
public final class LlamaDecorLayer implements LivingLayer {
    private static final int CHEST_ROOT = 6;

    private final EntityLlama llama;
    private final InstanceTree body;
    private final InstanceTree decor;
    private final VariantAtlasHolder atlas;

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;
    private VariantAtlas.Cell lastCell = null;

    public LlamaDecorLayer(VisualizationContext ctx, EntityLlama llama, InstanceTree body,
                           VariantAtlasHolder atlas, int bias) {
        this.llama = llama;
        this.body = body;
        this.atlas = atlas;
        this.decor = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(new LlamaEntityModel(0.5F), atlas.material(), "llama:decor"),
                bias, InstanceTypes.UV_TRANSFORMED);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        decor.visible(false);
    }

    // DECOR_SKINS (= cell add order) is indexed by EnumDyeColor.getMetadata().
    static int skinIndex(EntityLlama entity) {
        return entity.getColor().getMetadata();
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && llama.hasColor();
        if (show != shown) {
            shown = show;
            decor.visible(show);
            if (show) {
                lastLight = Integer.MIN_VALUE;
                lastOverlay = -1;
                lastCell = null;
            }
        }
        if (!show) {
            return;
        }

        int overlay = OverlayTexture.forEntity(llama);
        VariantAtlas.Cell cell = atlas.cell(skinIndex(llama));
        if (!bodyMoved && light == lastLight && overlay == lastOverlay && cell == lastCell) {
            // A hasChest flip forces bodyMoved true that frame, so the chest toggle is never missed here.
            return;
        }

        // Vanilla gates chests on !isChild too (mirrors the body's toggle in LlamaVisual.poseModel).
        boolean chest = !llama.isChild() && llama.hasChest();
        // A chest reveal reseeds the freed slots (identity UV, light 0), so it must force the state push below.
        boolean structureChanged = setSkipDraw(decor.child(CHEST_ROOT), !chest);
        structureChanged |= setSkipDraw(decor.child(CHEST_ROOT + 1), !chest);

        if (structureChanged || light != lastLight || overlay != lastOverlay || cell != lastCell) {
            lastLight = light;
            lastOverlay = overlay;
            lastCell = cell;
            decor.traverse(i -> {
                i.light(light);
                i.overlay(overlay);
                i.colorArgb(0xFFFFFFFF);
                ((UvTransformedInstance) i).uvRegion(cell);
            });
        }
        decor.copyComposedFrom(body);
    }

    private static boolean setSkipDraw(InstanceTree node, boolean skipDraw) {
        if (node.skipDraw() == skipDraw) {
            return false;
        }
        node.skipDraw(skipDraw);
        return true;
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            decor.visible(false);
        }
    }

    @Override
    public void delete() {
        decor.delete();
    }
}
