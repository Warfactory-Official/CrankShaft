void flw_materialVertex() {
    vec4 linePosStart = flw_viewProjection * flw_vertexPos;
    vec4 linePosEnd = flw_viewProjection * (flw_vertexPos + vec4(flw_vertexNormal, 0.));

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
