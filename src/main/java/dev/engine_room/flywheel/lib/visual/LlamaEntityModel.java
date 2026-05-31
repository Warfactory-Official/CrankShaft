package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelLlama;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelLlama} (a {@code ModelQuadruped}): head, body, four legs, then the two
 *  chest boxes (toggled per frame by the visual on {@code hasChest}), in render draw order. The quadruped fields
 *  are public; requires the access transformer on {@code ModelLlama.chest1}/{@code chest2}. {@code inflation}
 *  feeds the {@code ModelLlama} constructor — 0 for the body, 0.5 for the additive carpet decor overlay (same
 *  bone topology, so the decor copies the body's pose one-to-one). */
public final class LlamaEntityModel implements EntityModel<ModelLlama> {
    private final float inflation;

    public LlamaEntityModel() {
        this(0.0F);
    }

    public LlamaEntityModel(float inflation) {
        this.inflation = inflation;
    }

    @Override
    public ModelLlama create() {
        return new ModelLlama(inflation);
    }

    @Override
    public ModelRenderer[] roots(ModelLlama m) {
        return new ModelRenderer[] { m.head, m.body, m.leg1, m.leg2, m.leg3, m.leg4, m.chest1, m.chest2 };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelLlama m, int rootIndex) {
        // Per ModelLlama.render (baby branch); the 0.22 Z is a raw block unit in vanilla. Chests (roots 6, 7)
        // only draw on adults (vanilla gates them on !isChild), so they take the legs' transform moot-ly.
        if (rootIndex == 0) {
            dest.scale(0.71428573F, 0.64935064F, 0.7936508F).translate(0.0F, 21.0F * 0.0625F, 0.22F);
        } else if (rootIndex == 1) {
            dest.scale(0.625F, 0.45454544F, 0.45454544F).translate(0.0F, 33.0F * 0.0625F, 0.0F);
        } else {
            dest.scale(0.45454544F, 0.41322312F, 0.45454544F).translate(0.0F, 33.0F * 0.0625F, 0.0F);
        }
    }
}
