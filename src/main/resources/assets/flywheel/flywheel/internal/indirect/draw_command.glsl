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
    uint instanceType; // CrankShaft: shared guest draws select their instance layout.
};
