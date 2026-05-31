package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.HorseEntityModel;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.HorseArmorType;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4fc;

/**
 * Horse armor (iron/gold/diamond): re-instances the SAME horse geometry over the body at a higher bias. Sharing the
 * body's exact vertices and root matrix, the overlay lands at bit-identical depth, so under LEQUAL the armor draw
 * wins where opaque and alpha-discards elsewhere — no z-fight, no inflation. Custom armor textures fall back to vanilla.
 */
public final class HorseArmorLayer implements LivingLayer {
    // Armor skin per HorseArmorType ordinal (null for NONE), resolved once so the per-frame path
    // doesn't re-allocate a ResourceLocation.
    private static final ResourceLocation[] SKINS;
    static {
        HorseArmorType[] types = HorseArmorType.values();
        SKINS = new ResourceLocation[types.length];
        for (int i = 0; i < types.length; i++) {
            String texture = types[i].getTextureName();
            SKINS[i] = texture == null ? null : new ResourceLocation(texture);
        }
    }

    private final EntityHorse horse;
    private final InstanceTree body;
    private final InstanceTree armor;
    private final VariantAtlasHolder atlas;

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;
    private VariantAtlas.Cell lastCell = null;

    public HorseArmorLayer(VisualizationContext ctx, EntityHorse horse, InstanceTree body,
                           VariantAtlasHolder atlas, int bias) {
        this.horse = horse;
        this.body = body;
        this.atlas = atlas;
        this.armor = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(new HorseEntityModel(false), atlas.material(), "horse:armor"),
                bias, InstanceTypes.UV_TRANSFORMED);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        armor.visible(false);
    }

    static ResourceLocation skinFor(EntityHorse horse) {
        return SKINS[horse.getHorseArmorType().ordinal()];
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        ResourceLocation skin = skinFor(horse);
        boolean show = parentVisible && skin != null && atlas.contains(skin);
        if (show != shown) {
            shown = show;
            armor.visible(show);
            if (show) {
                lastLight = Integer.MIN_VALUE;
                lastOverlay = -1;
                lastCell = null;
            }
        }
        if (!show) {
            return;
        }

        int overlay = OverlayTexture.forEntity(horse);
        // ARMOR_ATLAS add order (iron, gold, diamond) = HorseArmorType ordinal - 1; the show gate's
        // contains(skin) keeps NONE/modded types out.
        VariantAtlas.Cell cell = atlas.cell(horse.getHorseArmorType().ordinal() - 1);
        if (!bodyMoved && light == lastLight && overlay == lastOverlay && cell == lastCell) {
            return;
        }

        // Mirror the body's tack/chest skipDraw: vanilla gates tack on isHorseSaddled, so the armor pass
        // must not draw armor-textured saddle/reins on an unsaddled horse. A mirrored reveal reseeds the
        // freed slots (light 0), so it forces the state push below.
        boolean structureChanged = false;
        for (int i = 0, n = body.childCount(); i < n; i++) {
            boolean skip = body.child(i).skipDraw();
            InstanceTree node = armor.child(i);
            if (node.skipDraw() != skip) {
                node.skipDraw(skip);
                structureChanged = true;
            }
        }

        if (structureChanged || light != lastLight || overlay != lastOverlay || cell != lastCell) {
            lastLight = light;
            lastOverlay = overlay;
            lastCell = cell;
            armor.traverse(i -> {
                i.light(light);
                i.overlay(overlay);
                i.colorArgb(0xFFFFFFFF);
                ((UvTransformedInstance) i).uvRegion(cell);
            });
        }
        armor.copyComposedFrom(body);
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            armor.visible(false);
        }
    }

    @Override
    public void delete() {
        armor.delete();
    }
}
