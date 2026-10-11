package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;

// TODO: DH API 7.3.0 setRenderDefinitionOverrideAsync + IDhApiVanillaFadeRenderer replace the Dh*FadeMixin hooks
// once a DH release ships it.
public final class DistantHorizonsFade {
    private static final float[] VALUES = new float[16];

    private DistantHorizonsFade() {
    }

    public static void prepare(DhApiRenderParam params) {
        LodDepthPass.prepareFadeMask(params.renderPass == EDhApiRenderPass.TRANSPARENT,
                joml(params.mcProjectionMatrix).mul(joml(params.mcModelViewMatrix)).invert());
    }

    public static GpuTexture mask() {
        return LodDepthPass.fadeMask();
    }

    public static int maskGlId() {
        return ((GlTexture) LodDepthPass.fadeMask()).glId();
    }

    static Matrix4f joml(DhApiMat4f matrix) {
        matrix.putValuesInArray(VALUES);
        return new Matrix4f().set(VALUES).transpose();
    }
}
