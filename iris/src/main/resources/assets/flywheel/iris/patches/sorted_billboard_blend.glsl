layout(std430, binding = 8) readonly buffer FlwSortedBlend {
    uint flw_sortedBlendActive;
    uint flw_sortedBlendDemand[128];
};
