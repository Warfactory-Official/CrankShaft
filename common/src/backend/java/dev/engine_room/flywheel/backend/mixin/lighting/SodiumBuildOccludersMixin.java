package dev.engine_room.flywheel.backend.mixin.lighting;

import dev.engine_room.flywheel.backend.lighting.SodiumOccluderCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkBuildOutput.class)
abstract class SodiumBuildOccludersMixin implements SodiumOccluderCapture.Carrier {
    @Unique
    private SodiumOccluderCapture.@Nullable Recording lighting$occluders;

    @Override
    public SodiumOccluderCapture.@Nullable Recording lighting$occluders() {
        return lighting$occluders;
    }

    @Override
    public void lighting$occluders(SodiumOccluderCapture.@Nullable Recording recording) {
        lighting$occluders = recording;
    }
}
