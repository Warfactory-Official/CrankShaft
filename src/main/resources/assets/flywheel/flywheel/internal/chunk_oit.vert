// Replay vanilla chunk-translucent VBOs through Flywheel's OIT pipeline. NVIDIA's compat-profile
// at #version 150 doesn't reliably feed gl_Vertex / gl_MultiTexCoord* from legacy glVertexPointer
// / glTexCoordPointer arrays, so we instead use modern glVertexAttribPointer with explicit
// locations (matching ChunkTranslucentOit.setupArrayPointers in Java). gl_ModelViewProjectionMatrix
// and gl_TextureMatrix[N] still read from vanilla's matrix stack — those are uniforms, not
// attribute streams, and work fine in compat.

layout(location = 0) in vec3 in_pos;
layout(location = 1) in vec4 in_color;
layout(location = 2) in vec2 in_uv0;
layout(location = 3) in vec2 in_uv1;

out vec4 v_color;
out vec2 v_uv0;
out vec2 v_uv1;
out float v_fogDistance;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * vec4(in_pos, 1.0);
    v_color = in_color;
    // Eye-space distance from the camera (origin in eye space) = spherical fog distance, matching
    // both the suppressed vanilla GL_LINEAR draw and Flywheel's instanced fog in the OIT composite.
    v_fogDistance = length((gl_ModelViewMatrix * vec4(in_pos, 1.0)).xyz);
    v_uv0 = (gl_TextureMatrix[0] * vec4(in_uv0, 0.0, 1.0)).xy;
    // Vanilla stores lightmap UV as GL_SHORT pixel coords (0..255). gl_TextureMatrix[1] is set by
    // EntityRenderer.enableLightmap to scale(1/256)*translate(8) — that normalises 240 -> ~0.97.
    v_uv1 = (gl_TextureMatrix[1] * vec4(in_uv1, 0.0, 1.0)).xy;
}
