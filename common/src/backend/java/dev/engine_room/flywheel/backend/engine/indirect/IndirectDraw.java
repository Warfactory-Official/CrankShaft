package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import org.lwjgl.system.MemoryUtil;

public class IndirectDraw {
    private final IndirectInstancer<?> instancer;
    private final Material material;
    private final MeshPool.PooledMesh mesh;
    private final int bias;
    private final int indexOfMeshInModel;

    private final int packedFogAndCutout;
    private final int packedMaterialProperties;
    private final int packedTexIndices;
    private boolean deleted;

    public IndirectDraw(IndirectInstancer<?> instancer, Material material, MeshPool.PooledMesh mesh, int bias,
                        int indexOfMeshInModel) {
        this.instancer = instancer;
        this.material = material;
        this.mesh = mesh;
        this.bias = bias;
        this.indexOfMeshInModel = indexOfMeshInModel;

        mesh.acquire();

        this.packedFogAndCutout = MaterialEncoder.packUberShader(material);
        this.packedMaterialProperties = MaterialEncoder.packProperties(material);
        int texSlot = VkCaps.BINDLESS_TEXTURES_NEGOTIATED || GlCompat.SUPPORTS_BINDLESS_TEXTURES
                ? BindlessSlots.slot(material) : 0;
        this.packedTexIndices = texSlot | (InstanceTypeIds.id(instancer.type) << 16);
    }

    public boolean deleted() {
        return deleted;
    }

    public Material material() {
        return material;
    }

    public InstanceType<?> instanceType() {
        return instancer.type;
    }

    public boolean isEmbedded() {
        return instancer.environment instanceof EmbeddedEnvironment;
    }

    public MeshPool.PooledMesh mesh() {
        return mesh;
    }

    public int bias() {
        return bias;
    }

    public int indexOfMeshInModel() {
        return indexOfMeshInModel;
    }

    public void write(long ptr) {
        MemoryUtil.memPutInt(ptr, mesh.indexCount()); // count
        MemoryUtil.memPutInt(ptr + 4, 0); // instanceCount - to be set by the apply shader
        MemoryUtil.memPutInt(ptr + 8, mesh.firstIndex()); // firstIndex
        MemoryUtil.memPutInt(ptr + 12, mesh.baseVertex()); // baseVertex
        MemoryUtil.memPutInt(ptr + 16, instancer.baseInstance()); // baseInstance

        MemoryUtil.memPutInt(ptr + 20, instancer.modelIndex()); // modelIndex

        MemoryUtil.memPutInt(ptr + 24, instancer.environment.matrixIndex()); // matrixIndex

        MemoryUtil.memPutInt(ptr + 28, packedFogAndCutout); // packedFogAndCutout
        MemoryUtil.memPutInt(ptr + 32, packedMaterialProperties); // packedMaterialProperties
        MemoryUtil.memPutInt(ptr + 36, mesh.vertexCount());
        MemoryUtil.memPutInt(ptr + 40, mesh.meshletBase());
        MemoryUtil.memPutInt(ptr + 44, packedTexIndices);
    }

    public void writeWithOverrides(long ptr, int instanceIndex, Material materialOverride) {
        MemoryUtil.memPutInt(ptr, mesh.indexCount()); // count
        MemoryUtil.memPutInt(ptr + 4, 1); // instanceCount - only drawing one instance
        MemoryUtil.memPutInt(ptr + 8, mesh.firstIndex()); // firstIndex
        MemoryUtil.memPutInt(ptr + 12, mesh.baseVertex()); // baseVertex
        MemoryUtil.memPutInt(ptr + 16, instancer.local2ObjectUintOffset(
                instanceIndex));

        MemoryUtil.memPutInt(ptr + 20, instancer.modelIndex()); // modelIndex

        MemoryUtil.memPutInt(ptr + 24, instancer.environment.matrixIndex()); // matrixIndex

        MemoryUtil.memPutInt(ptr + 28, MaterialEncoder.packUberShader(materialOverride)); // packedFogAndCutout
        MemoryUtil.memPutInt(ptr + 32, MaterialEncoder.packProperties(materialOverride)); // packedMaterialProperties
        MemoryUtil.memPutInt(ptr + 36, mesh.vertexCount());
        MemoryUtil.memPutInt(ptr + 40, mesh.meshletBase());
        MemoryUtil.memPutInt(ptr + 44, packedTexIndices);
    }

    public void delete() {
        if (deleted) {
            return;
        }

        mesh.release();

        deleted = true;
    }
}
