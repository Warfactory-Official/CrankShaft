#include "flywheel:internal/indirect/buffer_bindings.glsl"
#include "flywheel:internal/indirect/draw_command.glsl"
#include "flywheel:internal/indirect/model_descriptor.glsl"

layout(local_size_x = _FLW_SUBGROUP_SIZE) in;

layout(std430, binding = _FLW_MODEL_BUFFER_BINDING) restrict readonly buffer ModelBuffer {
    ModelDescriptor models[];
};

layout(std430, binding = _FLW_DRAW_BUFFER_BINDING) restrict buffer DrawBuffer {
    MeshDrawCommand drawCommands[];
};

#ifdef _FLW_VK
// Port: MoltenVK 1.4 push-descriptor length() = 0.
uniform uint _flw_drawCount;
#endif

// Apply the results of culling to the draw commands.
void main() {
    uint drawIndex = gl_GlobalInvocationID.x;

#ifdef _FLW_VK
    if (drawIndex >= _flw_drawCount) {
        return;
    }
#else
    if (drawIndex >= drawCommands.length()) {
        return;
    }
#endif

    uint modelIndex = drawCommands[drawIndex].modelIndex;
    drawCommands[drawIndex].instanceCount = models[modelIndex].instanceCount;
}
