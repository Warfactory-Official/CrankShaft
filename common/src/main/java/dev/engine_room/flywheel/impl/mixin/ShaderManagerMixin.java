package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.impl.FlwImpl;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.io.Reader;

@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
    private static final Identifier TRANSPARENCY_SOURCE = Identifier.withDefaultNamespace(
            "shaders/post/transparency.fsh");
    private static final String EMPTY_ALPHA = "if (color.a == 0.0)";

    // 26.2: adapt the raw source before preprocessing; RGB-only OIT emission has zero opacity but is not empty.
    @WrapOperation(method = "loadShader", at = @At(value = "INVOKE",
            target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/Reader;)Ljava/lang/String;"))
    private static String flw$admitEmission(Reader reader, Operation<String> original,
                                            @Local(argsOnly = true) Identifier location) throws IOException {
        String source = original.call(reader);
        if (!location.equals(TRANSPARENCY_SOURCE)) return source;
        int at = source.indexOf(EMPTY_ALPHA);
        if (at < 0 || source.indexOf(EMPTY_ALPHA, at + EMPTY_ALPHA.length()) >= 0) {
            FlwImpl.LOGGER.warn(
                    "Resource-pack {} has no single vanilla empty-layer test; Fabulous drops RGB-only OIT emission",
                    location);
            return source;
        }
        return source.replace(EMPTY_ALPHA, "if (all(equal(color, vec4(0.0))))");
    }
}
