struct TerrainDraw {
    uint count;
    uint instanceCount;
    uint firstIndex;
    uint baseVertex;
    uint baseInstance;
    uint wordOffset;
    float x;
    float y;
    float z;
    uint strideWords;
};
layout(std430, binding = 1) restrict readonly buffer _FlwTerrainVertices { uint _flw_terrainVertices[]; };
layout(std430, binding = 4) restrict readonly buffer _FlwTerrainDraws { TerrainDraw _flw_terrainDraws[]; };
uniform uint _flw_baseDraw;
#if __VERSION__ < 460
#define _flw_drawId gl_DrawIDARB
#else
#define _flw_drawId gl_DrawID
#endif
