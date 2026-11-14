package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
import net.minecraft.client.renderer.DynamicGpuDataStorage.DynamicGpuData;
#else
import net.minecraft.client.renderer.DynamicUniformStorage.DynamicUniform;
#endif

#if MC >= 26.3
public typealias UniformData = DynamicGpuData;
#else
public typealias UniformData = DynamicUniform;
#endif
