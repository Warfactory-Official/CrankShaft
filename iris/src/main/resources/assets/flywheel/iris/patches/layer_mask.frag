#version 430 core
layout(std430, binding=FLW_REPLAY_TILES) readonly buffer Tiles { uint tiles[]; };
uniform ivec2 tileCount;
uniform int layerIndex;
void main() {
    ivec2 tile = ivec2(gl_FragCoord.xy) >> FLW_REPLAY_TILE_SHIFT;
    if (tiles[tileCount.x * tileCount.y + tile.y * tileCount.x + tile.x] <= uint(layerIndex)) discard;
}
