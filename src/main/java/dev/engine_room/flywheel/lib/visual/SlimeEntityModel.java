package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSlime;

/** {@link EntityModel} for the inner {@link ModelSlime}(16): the inner body cube plus the two eyes and
 *  mouth (render order — body first). Pair with {@code SlimeGelModel} for the translucent outer gel.
 *  Requires the access transformer on {@code ModelSlime.slimeBodies}/{@code slimeRightEye}/
 *  {@code slimeLeftEye}/{@code slimeMouth}. */
public final class SlimeEntityModel implements EntityModel<ModelSlime> {
    @Override
    public ModelSlime create() {
        return new ModelSlime(16);
    }

    @Override
    public ModelRenderer[] roots(ModelSlime m) {
        return new ModelRenderer[] { m.slimeBodies, m.slimeRightEye, m.slimeLeftEye, m.slimeMouth };
    }
}
