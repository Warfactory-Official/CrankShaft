package dev.engine_room.flywheel.backend.mixin.mod.nothirium;

import dev.engine_room.flywheel.backend.engine.shaderpack.NativeTerrainBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "meldexun.nothirium.opengl.DynamicVBO$VBOPart", remap = false)
public abstract class NativeTerrainPartMixin implements NativeTerrainBuffer {
    @Shadow public abstract boolean isValid();

    @Override public boolean flywheel$isValid() { return isValid(); }
}
