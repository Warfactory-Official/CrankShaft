struct MeshDrawCommand {
    uint indexCount;
    uint instanceCount;
    uint firstIndex;
    uint vertexOffset;
    uint baseInstance;

    uint modelIndex;
    uint matrixIndex;

    uint packedFogAndCutout;
    uint packedMaterialProperties;

    uint vertexCount; // mesh unique-vertex count, for the gl_mesh_shader welded decode (ignored by the MDI draw)

    uint meshletBase; // gl_mesh_shader task-cull: base index of this mesh's per-meshlet bounding spheres (ignored by the MDI draw)

    uint packedTexIndices; // lo16: bindless material texture slot (0 on the classic binding path); hi16: instance typeId (uber vertex dispatch)
};
