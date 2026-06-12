// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_VK_HIZ_UNIFORMS_GLSL
#define MESHLET_VK_HIZ_UNIFORMS_GLSL

layout(set = 0, binding = 5, std140) uniform TerrainHiZUniforms {
    mat4 viewProjection;
    vec4 cameraPosAndPad;
    ivec4 cameraBlockPosAndPad;
};

#endif
