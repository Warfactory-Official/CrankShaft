package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelDragon;
import net.minecraft.client.model.ModelRenderer;

/**
 * {@link EntityModel} selecting ONE {@link ModelDragon} part as a single root (sub-parts recurse via {@code childModels}).
 * The dragon animates in {@code ModelDragon.render} (not {@code setRotationAngles}) and the spine draws 17× along a ring,
 * so {@code EnderDragonVisual} bakes each part once and poses by hand. Root fields are private — needs the access transformer.
 */
public final class DragonEntityModel implements EntityModel<ModelDragon> {
    public static final int HEAD = 0;
    public static final int BODY = 1;
    public static final int WING = 2;
    public static final int FRONT_LEG = 3;
    public static final int REAR_LEG = 4;
    public static final int SPINE = 5;

    private final int part;

    public DragonEntityModel(int part) {
        this.part = part;
    }

    @Override
    public ModelDragon create() {
        return new ModelDragon(0.0F);
    }

    @Override
    public ModelRenderer[] roots(ModelDragon m) {
        return switch (part) {
            case HEAD -> new ModelRenderer[] { m.head };
            case BODY -> new ModelRenderer[] { m.body };
            case WING -> new ModelRenderer[] { m.wing };
            case FRONT_LEG -> new ModelRenderer[] { m.frontLeg };
            case REAR_LEG -> new ModelRenderer[] { m.rearLeg };
            case SPINE -> new ModelRenderer[] { m.spine };
            default -> throw new IllegalArgumentException("Unknown dragon part " + part);
        };
    }
}
