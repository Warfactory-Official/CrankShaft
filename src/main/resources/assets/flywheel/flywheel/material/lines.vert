void flw_materialVertex() {
    vec4 linePosStart = flw_viewProjection * flw_vertexPos;
    vec4 linePosEnd = flw_viewProjection * (flw_vertexPos + vec4(flw_vertexNormal, 0.));

    // Near-plane clip: if this vertex lies at or behind the camera near plane (clip.w <= 0),
    // slide it forward along the line direction (via flw_vertexNormal) until clip.w reaches
    // NEAR_EPSILON. Without this, when one endpoint of a line is behind the camera the
    // rasterizer clips against the near plane and the perpendicular offset (+/-lineOffset * w)
    // cancels exactly at the intersection — visible as a triangular taper (zero width at the
    // apex, full lineWidth at the far endpoint). After the shift both endpoints have w > 0
    // and the +/- offset contributions never cross zero, so the quad stays a parallelogram.
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

    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * flw_viewportSize);
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * flw_defaultLineWidth / flw_viewportSize;

    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    if (gl_VertexID % 2 == 0) {
        flw_vertexPos = flw_viewProjectionInverse * vec4((ndc1 + vec3(lineOffset, 0.)) * linePosStart.w, linePosStart.w);
    } else {
        flw_vertexPos = flw_viewProjectionInverse * vec4((ndc1 - vec3(lineOffset, 0.)) * linePosStart.w, linePosStart.w);
    }
}
