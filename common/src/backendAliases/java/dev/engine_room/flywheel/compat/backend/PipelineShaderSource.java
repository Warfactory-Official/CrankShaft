package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.AssembledShaderSource;
#else
import com.mojang.blaze3d.shaders.ShaderSource;
#endif

#if MC >= 26.3
public typealias PipelineShaderSource = AssembledShaderSource;
#else
public typealias PipelineShaderSource = ShaderSource;
#endif
