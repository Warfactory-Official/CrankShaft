vec3 _flw_forwardSeusGlassTransmission(GBufferDataTransparent material) {
    // Transparent G-buffer decoding linearises albedo without the opaque color-space transform.
    vec4 albedo = vec4(GammaToLinear(material.albedo.rgb), material.albedo.a);
    return _flw_forwardSeusGlassTint(albedo);
}
