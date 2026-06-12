// Task->mesh payload: welded children use arg0 + gl_WorkGroupID.x; unwelded (task-culled) read remap[gl_WorkGroupID.x].
struct MeshVisualPayload {
    uint drawIndex;
    uint arg0;
    uint remap[64];
};

taskPayloadSharedEXT MeshVisualPayload payload;
