// Port: glint.vert at the armor glint UV density -- vanilla 26.2 scales armor glint by 0.16
// (TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING), not the item glint's 8. See glint.vert for the strength dim.
void flw_materialVertex() {
    float p = flw_glintSpeedOption * flw_systemSeconds * 8.;

    flw_vertexTexCoord *= 0.16;
    flw_vertexTexCoord *= mat2(0.98480775, -0.17364817, 0.17364817, 0.98480775);
    flw_vertexTexCoord += vec2(-p / 110., p / 30.);

    flw_vertexColor.rgb *= flw_glintStrengthOption;
}
