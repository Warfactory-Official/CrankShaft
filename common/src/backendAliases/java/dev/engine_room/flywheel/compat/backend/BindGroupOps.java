package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
#else
import com.mojang.blaze3d.pipeline.BindGroupLayout;
#endif

/** Expands to the native combined image-sampler declaration for the selected Minecraft version. */
public macro class BindGroupOps {
    BindGroupLayout.Builder BindGroupLayout.Builder.withImageSampler(String name) {
        #if MC >= 26.3
        return this.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        #else
        return this.withSampler(name);
        #endif
    }
}
