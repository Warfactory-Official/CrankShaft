package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(CustomUniforms.class)
public interface CustomUniformsAccessor {
    @Accessor("locationMap")
    Map<Object, ?> flywheel$locations();
}
