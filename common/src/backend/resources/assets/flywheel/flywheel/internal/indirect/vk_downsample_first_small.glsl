layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 10) uniform sampler2D depth_tex;
layout(binding = 1, r32f) uniform restrict writeonly image2D mip_0;

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(pix, imageSize(mip_0)))) {
        return;
    }
    vec2 invSize = 1.0 / vec2(textureSize(depth_tex, 0));
    vec2 uv = min((vec2(pix * 2) + 0.75) * invSize, 1.0 - invSize);
    vec4 v = textureGather(depth_tex, uv);
    imageStore(mip_0, pix, vec4(min(min(v.x, v.y), min(v.z, v.w))));
}
