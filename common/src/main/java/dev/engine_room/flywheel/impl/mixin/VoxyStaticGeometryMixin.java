package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.far.VoxyStaticSections;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BasicSectionGeometryData.class)
abstract class VoxyStaticGeometryMixin implements VoxyStaticVisuals.GeometryAccess {
    @Unique
    private VoxyStaticSections flywheel$staticSections;

    @Override
    public VoxyStaticSections flywheel$staticSections() {
        return flywheel$staticSections;
    }

    @Override
    public void flywheel$staticSections(VoxyStaticSections sections) {
        flywheel$staticSections = sections;
    }
}

#endif
