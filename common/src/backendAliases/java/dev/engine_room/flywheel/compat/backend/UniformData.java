package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
public typealias UniformData = net.minecraft.client.renderer.DynamicGpuDataStorage.DynamicGpuData;
#else
public typealias UniformData = net.minecraft.client.renderer.DynamicUniformStorage.DynamicUniform;
#endif
