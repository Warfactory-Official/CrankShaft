struct BoundingSphere {
    float x;
    float y;
    float z;
    float radius;
};

struct ModelDescriptor {
    uint instanceCount;
    uint baseInstance;
    uint matrixIndex;
    BoundingSphere boundingSphere;
};

// Port: matrixIndex flags (IndirectInstancer.writeModel).
const uint _FLW_MODEL_NO_OCCLUSION = 0x80000000u;
// Visible instances keep page order: the cull leaves them to the ordered compaction (order.glsl).
const uint _FLW_MODEL_ORDERED = 0x40000000u;
const uint _FLW_MODEL_MATRIX_MASK = 0x3FFFFFFFu;

void _flw_unpackBoundingSphere(in BoundingSphere sphere, out vec3 center, out float radius) {
    center = vec3(sphere.x, sphere.y, sphere.z);
    radius = sphere.radius;
}
