package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSpider;

public final class SpiderEntityModel implements EntityModel<ModelSpider> {
    @Override
    public ModelSpider create() {
        return new ModelSpider();
    }

    @Override
    public ModelRenderer[] roots(ModelSpider m) {
        return new ModelRenderer[] {
                m.spiderHead, m.spiderNeck, m.spiderBody,
                m.spiderLeg1, m.spiderLeg2, m.spiderLeg3, m.spiderLeg4,
                m.spiderLeg5, m.spiderLeg6, m.spiderLeg7, m.spiderLeg8,
        };
    }
}
