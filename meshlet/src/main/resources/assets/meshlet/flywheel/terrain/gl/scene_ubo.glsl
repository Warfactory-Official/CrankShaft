// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_GL_SCENE_UBO_GLSL
#define MESHLET_GL_SCENE_UBO_GLSL

layout(std140, binding = 7) uniform TerrainSceneUbo {
    restrict readonly uvec4 *u_regionInput;
    restrict readonly uvec4 *u_sectionData0;
    restrict readonly uint  *u_regionVis;
    restrict uint           *u_sectionVis;
    restrict writeonly uvec2 *u_meshTaskCommands;
    restrict writeonly uvec2 *u_regionCommandCount;
    restrict readonly float *u_sectionFadeVis;
    uint u_regionCount;
};

#endif
