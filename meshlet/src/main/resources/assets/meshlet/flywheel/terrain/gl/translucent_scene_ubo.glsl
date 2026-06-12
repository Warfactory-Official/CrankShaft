// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_GL_TRANSLUCENT_SCENE_UBO_GLSL
#define MESHLET_GL_TRANSLUCENT_SCENE_UBO_GLSL

layout(std140, binding = 7) uniform TerrainSceneUbo {
    restrict readonly uvec4 *u_regionInput;
    restrict readonly uvec4 *u_sectionData0;
    restrict readonly uint  *u_regionVis;
    restrict readonly float *u_translucentVis;
    restrict uint  *u_meshTaskCommands;
    restrict readonly uvec2  *u_regionCommandCount;
    restrict readonly uint   *u_unused48;
    uint u_regionCount;
};

#endif
