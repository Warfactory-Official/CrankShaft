package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.compat.dh.DHCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DHCompat.class)
public interface DHCompatAccessor {
    @Invoker("getProjection")
    static Matrix4f flywheel$getProjection(float nearPlane, float farPlane) {
        throw new AssertionError();
    }
}
