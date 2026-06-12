// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#include "meshlet:terrain/gl/vertex_format/sodium_vertex_format.glsl"

#ifdef MESHLET_BARYCENTRIC
layout(std430, binding = 12) restrict readonly buffer GeometryPointers {
    restrict Vertex *geometryPtrs[];
};
layout(location = 1) in PerVertex {
    flat uvec2 quadRef;
    vec2 fog;
} v_in;
#else
layout(location = 1) in PerVertex {
    vec4 uvLight;
    vec3 colour;
    vec2 fog;
} v_in;
#endif

layout(binding = 0) uniform sampler2D Sampler0;
layout(binding = 2) uniform sampler2D Sampler2;

layout(std140, binding = 9) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

layout(location = 0) out vec4 fragColor;

#ifdef MESHLET_SOLID_PASS
layout(early_fragment_tests) in;
#endif

float alphaCutoff(uint id) {
    return float[](0.0, 0.0001, 0.5, 1.0)[id];
}

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float total_fog_value(float spherical, float cylindrical, float envStart, float envEnd, float rdStart, float rdEnd) {
    return max(linear_fog_value(spherical, envStart, envEnd), linear_fog_value(cylindrical, rdStart, rdEnd));
}

vec4 apply_fog(vec4 inColor, float spherical, float cylindrical, float envStart, float envEnd, float rdStart, float rdEnd, vec4 fogColor) {
    float fogValue = total_fog_value(spherical, cylindrical, envStart, envEnd, rdStart, rdEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

#ifdef MESHLET_RGSS
vec2 texelSize;

vec4 sampleNearest(vec2 uv, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / texelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - 0.5) * texelSize / texelScreenSize + 0.5;
    texelOffset = clamp(texelOffset, 0.0, 1.0);
    return textureGrad(Sampler0, (texelCenter + texelOffset) * texelSize, du, dv);
}

vec4 sampleRGSS(vec2 uv, vec2 du, vec2 dv, vec2 texelScreenSize) {
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(texelSize.x, texelSize.y);
    float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float effectiveDerivative = sqrt(min(duLength, dvLength) * max(duLength, dvLength));
    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
        vec2(0.125, 0.375), vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(-0.375, 0.125));
    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        rgssColor += textureLod(Sampler0, uv + offsets[i] * texelSize, mipLevelExact);
    }
    rgssColor *= 0.25;

    return mix(sampleNearest(uv, du, dv, texelScreenSize), rgssColor, blendFactor);
}
#endif

void main() {
#ifdef MESHLET_BARYCENTRIC
    restrict Vertex *qp = geometryPtrs[v_in.quadRef.x] + v_in.quadRef.y;
    uvec3 ti = ((gl_PrimitiveID & 1) == 0) ? uvec3(0u, 1u, 2u) : uvec3(0u, 2u, 3u);
    Vertex V0 = qp[ti.x];
    Vertex V1 = qp[ti.y];
    Vertex V2 = qp[ti.z];
    vec3 bary = gl_BaryCoordNV;

    vec2 uv0 = decodeVertexUV(V0), uv1 = decodeVertexUV(V1), uv2 = decodeVertexUV(V2);
    vec2 uvr = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float halfShift = 0.5 / float(TEXTURE_MAX_COORD);
    vec2 uv = clamp(uvr, min(min(uv0, uv1), uv2) + halfShift, max(max(uv0, uv1), uv2) - halfShift);

    vec2 lightUV = bary.x * decodeLightUV(V0) + bary.y * decodeLightUV(V1) + bary.z * decodeLightUV(V2);
    vec3 tint = bary.x * decodeVertexColour(V0).rgb + bary.y * decodeVertexColour(V1).rgb
              + bary.z * decodeVertexColour(V2).rgb;
    uint cutoffId = decodeAlphaCutoffId(V0);
    float fade = float((uint(gl_PrimitiveID) >> 4u) & 0xFFFu) * (1.0 / 4095.0);
    vec2 fogDist = v_in.fog;
#else
    vec2 uv = v_in.uvLight.xy;
    vec2 lightUV = v_in.uvLight.zw;
    vec3 tint = v_in.colour;
    uint cutoffId = (uint(gl_PrimitiveID) >> 2u) & 3u;
    float fade = float((uint(gl_PrimitiveID) >> 4u) & 0xFFFu) * (1.0 / 4095.0);
    vec2 fogDist = v_in.fog;
#endif

#ifdef MESHLET_RGSS
    texelSize = 1.0 / vec2(textureSize(Sampler0, 0));
    #ifdef MESHLET_BARYCENTRIC
    vec2 du = dFdx(uvr);
    vec2 dv = dFdy(uvr);
    #else
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    #endif
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    vec4 texel = sampleRGSS(uv, du, dv, texelScreenSize);
#else
    vec4 texel = texture(Sampler0, uv);
#endif

#ifndef MESHLET_SOLID_PASS
    if (texel.a < alphaCutoff(cutoffId)) {
        discard;
    }
#endif

    vec3 light = texture(Sampler2, lightUV).rgb;
    vec4 color = vec4(texel.rgb * tint * light, 1.0);

    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, fade);
    color = apply_fog(color, fogDist.x, fogDist.y,
            FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    fragColor = color;
}
