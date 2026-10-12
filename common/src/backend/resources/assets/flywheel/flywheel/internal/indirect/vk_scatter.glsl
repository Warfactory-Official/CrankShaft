layout(local_size_x = 32) in;

layout(std430, binding = 0) restrict readonly buffer Copies {
    uvec2 copies[];
};

layout(std430, binding = 1) restrict readonly buffer Src {
    uint src[];
};

layout(std430, binding = 2) restrict writeonly buffer Dst {
    uint dst[];
};

uniform uint _flw_firstCopy;

void main() {
    // Source snapshots use the same offsets as the destination object storage.
    uvec2 copy = copies[_flw_firstCopy + gl_WorkGroupID.x];
    for (uint i = gl_LocalInvocationID.x; i < copy.y; i += 32u) {
        dst[copy.x + i] = src[copy.x + i];
    }
}
