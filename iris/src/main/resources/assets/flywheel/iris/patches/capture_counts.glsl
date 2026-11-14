layout(std430, binding = _FLW_COUNTS_BINDING) buffer FlwCounts {
    coherent uint flw_count;
    coherent uint flw_overflow;
    coherent uint flw_maxLayers;
    coherent uint flw_pad;
    // Keep the read-mostly draw flag away from the allocation atomics.
    uint flw_countsPadding[28];
    // Material draws only set this; storage barriers publish it to light draws and composites.
    uint flw_needsLight;
};
