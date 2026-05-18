in vec3 _flw_aPos;
in vec4 _flw_aColor;
in vec2 _flw_aTexCoord;
in vec2 _flw_aOverlay;
in vec2 _flw_aLight;
in vec3 _flw_aNormal;

void _flw_layoutVertex() {
    flw_vertexPos = vec4(_flw_aPos, 1.0);
    flw_vertexColor = _flw_aColor;
    flw_vertexTexCoord = _flw_aTexCoord;
    // Integer vertex attributes explode on some drivers for some draw calls, so get the driver
    // to cast the int to a float so we can cast it back to an int and reliably get a sane value.
    flw_vertexOverlay = ivec2(_flw_aOverlay);
    // Vanilla 1.12.2 EntityRenderer applies a half-texel offset to the lightmap UV via the
    // GL_TEXTURE matrix (glScalef(1/256) * glTranslatef(8, 8, 8) — see RenderHelper /
    // EntityRenderer.updateFogColor / setupLightmapCoords path). That puts sample points at
    // texel centers (e.g. block=0 → u=0.5/16, sky=15 → v=15.5/16). Our shader was scaling
    // without the offset, landing UV (0, 0.9375) on a texel boundary; with GL_LINEAR filter
    // on the lightmap, the sky=15 case averaged 50% texel 14 + 50% texel 15. At night that's
    // visibly dimmer than vanilla's texel-15-center sample. Match vanilla by adding the +8
    // pre-scale.
    flw_vertexLight = (_flw_aLight + 8.0) / 256.0;
    flw_vertexNormal = _flw_aNormal;
}
