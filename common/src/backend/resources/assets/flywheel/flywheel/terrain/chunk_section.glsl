#ifndef FLW_TERRAIN_CHUNK_SECTION_GLSL
#define FLW_TERRAIN_CHUNK_SECTION_GLSL

// Engine terrain producers share this private 96-byte block. Vanilla 26.3 split ModelViewMat
// into TerrainUniform; keeping the combined layout matches the engine's GL/Vulkan writers
// and the matrix prefix consumed by the mesh terrain paths.
layout(std140) uniform ChunkSection {
    mat4 ModelViewMat;
    float ChunkVisibility;
    ivec2 TextureSize;
    ivec3 ChunkPosition;
};

#endif
