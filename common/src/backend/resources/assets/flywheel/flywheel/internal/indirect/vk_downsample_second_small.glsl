layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0, r32f) uniform restrict readonly image2D mip_0;
layout(binding = 1, r32f) uniform restrict writeonly image2D mip_1;

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(pix, imageSize(mip_1)))) {
        return;
    }
    ivec2 tex = pix * 2;
    vec4 v = vec4(imageLoad(mip_0, tex).r,
                  imageLoad(mip_0, tex + ivec2(0, 1)).r,
                  imageLoad(mip_0, tex + ivec2(1, 0)).r,
                  imageLoad(mip_0, tex + ivec2(1, 1)).r);
    imageStore(mip_1, pix, vec4(min(min(v.x, v.y), min(v.z, v.w))));
}
