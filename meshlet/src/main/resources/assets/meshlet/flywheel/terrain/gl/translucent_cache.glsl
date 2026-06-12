// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_GL_TRANSLUCENT_CACHE_GLSL
#define MESHLET_GL_TRANSLUCENT_CACHE_GLSL

struct CachedVert {
    float px, py, pz;
    uint uv;
    uint colorRG;
    uint colorBA;
};

#endif
