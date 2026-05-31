void flw_materialVertex() {
    float p = flw_glintSpeedOption * flw_systemSeconds * 8.;

    flw_vertexTexCoord += vec2(0., p / 5.);
    flw_vertexTexCoord *= mat2(0.8660254, 0.5, -0.5, 0.8660254);
    flw_vertexTexCoord *= 0.33333334;

    flw_vertexColor = vec4(0.38, 0.19, 0.608, 1.0);
}
