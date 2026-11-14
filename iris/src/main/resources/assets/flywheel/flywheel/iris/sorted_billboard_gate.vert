layout(std430, binding = _FLW_SORTED_BLEND_BINDING) readonly buffer _FlwSortedBlendFlag {
    uint _flw_sortedBlendActive;
};

bool _flw_sortedBlendDrawEnabled() {
    return (_flw_sortedBlendActive != 0u) == bool(_FLW_SORTED_BLEND_FAST);
}
