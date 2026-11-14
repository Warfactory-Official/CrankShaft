uint flw_particleOffset(uvec2 pixel, uint width) {
    return (pixel.y * (width / 2u + 1u) + pixel.x) * 3u;
}

uint flw_particleSlots(uvec2 size) {
    return (size.x / 2u + 1u) * (size.y / 2u + 1u) * 3u;
}
