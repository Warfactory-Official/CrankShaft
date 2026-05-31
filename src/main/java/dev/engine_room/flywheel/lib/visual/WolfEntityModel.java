package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelWolf;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelWolf}'s eight parts, in render draw order. Cannot reuse
 *  {@link QuadrupedEntityModel} (that exposes only six roots and would drop the tail + mane). Requires the
 *  access transformer on {@code ModelWolf.wolfTail}/{@code wolfMane} (the head/body/legs are public). */
public final class WolfEntityModel implements EntityModel<ModelWolf> {
    private static final float MODEL_SCALE = 0.0625F;

    @Override
    public ModelWolf create() {
        return new ModelWolf();
    }

    @Override
    public ModelRenderer[] roots(ModelWolf m) {
        return new ModelRenderer[] {
                m.wolfHeadMain, m.wolfBody, m.wolfLeg1, m.wolfLeg2, m.wolfLeg3, m.wolfLeg4, m.wolfTail, m.wolfMane,
        };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelWolf m, int rootIndex) {
        // Per ModelWolf.render: the head (root 0) translates only (no scale); the rest is the 0.5 group.
        if (rootIndex == 0) {
            dest.translate(0.0F, 5.0F * MODEL_SCALE, 2.0F * MODEL_SCALE);
        } else {
            dest.scale(0.5F).translate(0.0F, 24.0F * MODEL_SCALE, 0.0F);
        }
    }
}
