// Per culling group
#define _FLW_PAGE_FRAME_DESCRIPTOR_BUFFER_BINDING 0// cull
#define _FLW_INSTANCE_BUFFER_BINDING 1// cull, draw
#define _FLW_DRAW_INSTANCE_INDEX_BUFFER_BINDING 2// cull, draw
#define _FLW_MODEL_BUFFER_BINDING 3// cull, apply
#define _FLW_DRAW_BUFFER_BINDING 4// apply, draw

// Global to the engine
#define _FLW_LIGHT_LUT_BUFFER_BINDING 5
#define _FLW_LIGHT_SECTIONS_BUFFER_BINDING 6

#define _FLW_MATRIX_BUFFER_BINDING 7

// Port: ordered compaction (order.glsl); the apply and draw after it rebind these slots.
#define _FLW_ORDERED_MODEL_BUFFER_BINDING 1
#define _FLW_ORDERED_PAGE_BUFFER_BINDING 4
#define _FLW_VIS_WORDS_BUFFER_BINDING 6
