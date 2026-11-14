package dev.engine_room.flywheel.impl.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.engine_room.flywheel.impl.far.VoxyFarSource;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

@Mixin(WorldEngine.class)
abstract class VoxyFarSourceMixin implements VoxyFarSource.Access {
    @Unique
    private volatile @Nullable VoxyFarSource flywheel$farSource;

    @Override
    public @Nullable VoxyFarSource flywheel$getFarSource() {
        return flywheel$farSource;
    }

    @Override
    public void flywheel$setFarSource(@Nullable VoxyFarSource source) {
        flywheel$farSource = source;
    }

    @Inject(method = "markDirty(Lme/cortex/voxy/common/world/WorldSection;II)V", at = @At("RETURN"))
    private void flywheel$farSectionChanged(WorldSection section, int changeState, int _, CallbackInfo _) {
        var source = flywheel$farSource;
        if (source != null && section.lvl == 0 && (changeState & WorldEngine.UPDATE_TYPE_BLOCK_BIT) != 0) {
            source.changed(section.x, section.z);
        }
    }
}
