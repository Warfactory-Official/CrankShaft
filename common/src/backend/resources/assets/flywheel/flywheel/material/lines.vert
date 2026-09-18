#include "flywheel:material/line_frame.glsl"

#ifdef FLW_LINE_VK_MESH
vec4 flw_lineClipPosition;
#endif

vec4 flw_lineProject(vec4 position) {
    const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
#ifdef FLW_LINE_VK_MESH
    const mat4 VIEW_SCALE = mat4(
        VIEW_SHRINK, 0., 0., 0.,
        0., VIEW_SHRINK, 0., 0.,
        0., 0., VIEW_SHRINK, 0.,
        0., 0., 0., 1.);
    return ProjMat * VIEW_SCALE * _flw_mvModelView * position;
#else
    // 26.2: P * VIEW_SCALE * V * p = shrink * (P * V * p) + (1-shrink) * p.w * P[3].
    // The frame VP carries the renderer's origin translation; ModelViewMat alone bypasses it.
    return VIEW_SHRINK * (_flw_lineViewProjection * position)
            + (1.0 - VIEW_SHRINK) * position.w * _flw_lineProjection[3];
#endif
}

void flw_materialVertex() {
    vec4 linePosStart = flw_lineProject(flw_vertexPos);
    vec4 endpoint = flw_vertexPos + vec4(flw_vertexNormal, 0.);
    vec4 linePosEnd = flw_lineProject(endpoint);

    // Near-plane clip: slide behind-camera endpoints forward so the +/- offset never cancels at the near plane.
    const float NEAR_EPSILON = 1e-3;
    if (linePosStart.w < NEAR_EPSILON) {
        float deltaW = linePosEnd.w - linePosStart.w;
        if (abs(deltaW) > 1e-6) {
            float t = (NEAR_EPSILON - linePosStart.w) / deltaW;
            vec4 delta = linePosEnd - linePosStart;
            linePosStart += t * delta;
            linePosEnd = linePosStart + delta;
            flw_vertexPos += t * vec4(flw_vertexNormal, 0.);
        }
    }

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;

    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * _flw_lineViewportSize);
    float pixelWidth = flw_vertexTexCoord.x > 0. ? flw_vertexTexCoord.x : _flw_lineDefaultWidth;
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * pixelWidth / _flw_lineViewportSize;

    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    vec4 clipPosition = vec4((ndc1 + vec3(flw_vertexTexCoord.y < .5 ? lineOffset : -lineOffset, 0.))
            * linePosStart.w, linePosStart.w);
#ifdef FLW_LINE_VK_MESH
    flw_lineClipPosition = clipPosition;
#else
    flw_vertexPos = _flw_lineViewProjectionInverse * clipPosition;
#endif
    flw_vertexTexCoord = vec2(0.);
}
