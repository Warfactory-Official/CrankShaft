#version 430 core
layout(std430, binding=3) readonly buffer Tiles { uint tiles[]; };
uniform ivec2 tileCount;
uniform int layerIndex;
void main() {
    ivec2 tile = ivec2(gl_FragCoord.xy) >> 4;
    if (tiles[tileCount.x * tileCount.y + tile.y * tileCount.x + tile.x] <= uint(layerIndex)) discard;
}
