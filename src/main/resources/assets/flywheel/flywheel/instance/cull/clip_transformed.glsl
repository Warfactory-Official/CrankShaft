#include "flywheel:util/matrix.glsl"

void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    center += i.slide;
    transformBoundingSphere(i.pose, center, radius);
}
