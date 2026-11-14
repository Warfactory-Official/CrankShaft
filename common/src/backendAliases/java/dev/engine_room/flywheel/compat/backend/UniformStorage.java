package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
import net.minecraft.client.renderer.DynamicGpuDataStorage.DynamicGpuData;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
#else
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.DynamicUniformStorage.DynamicUniform;
#endif

#if MC >= 26.3
public typealias UniformStorage<T extends DynamicGpuData> =
        DynamicGpuDataStorageMapped<T>;
#else
public typealias UniformStorage<T extends DynamicUniform> =
        DynamicUniformStorage<T>;
#endif
