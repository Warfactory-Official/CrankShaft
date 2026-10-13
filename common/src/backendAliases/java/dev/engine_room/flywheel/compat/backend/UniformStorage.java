package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
public typealias UniformStorage<T extends net.minecraft.client.renderer.DynamicGpuDataStorage.DynamicGpuData> =
        net.minecraft.client.renderer.DynamicGpuDataStorageMapped<T>;
#else
public typealias UniformStorage<T extends net.minecraft.client.renderer.DynamicUniformStorage.DynamicUniform> =
        net.minecraft.client.renderer.DynamicUniformStorage<T>;
#endif
