package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
public typealias PipelineShaderSource = dev.engine_room.flywheel.backend.compile.AssembledShaderSource;
#else
public typealias PipelineShaderSource = com.mojang.blaze3d.shaders.ShaderSource;
#endif
