package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
#else
import net.minecraft.client.renderer.DynamicUniformStorage;
#endif

/** Expands to the native mapped uniform storage for the selected Minecraft version. */
public macro class UniformStorageOps {
    #if MC >= 26.3
    <T extends DynamicGpuDataStorage.DynamicGpuData> DynamicGpuDataStorageMapped<T> create(String label, int size,
                                                                                         int capacity) {
        return new DynamicGpuDataStorageMapped<>(label, size, GpuBuffer.USAGE_UNIFORM, capacity);
    }

    <T extends DynamicGpuDataStorage.DynamicGpuData> GpuBufferSlice DynamicGpuDataStorageMapped<T>.writeUniform(
            T uniform) {
        return this.writeData(uniform);
    }
    #else
    <T extends DynamicUniformStorage.DynamicUniform> DynamicUniformStorage<T> create(String label, int size,
                                                                                    int capacity) {
        return new DynamicUniformStorage<>(label, size, capacity);
    }
    #endif
}
