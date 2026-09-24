void _flw_guestVertexLight(uint packedProperties) {
    _flw_unpackMaterialProperties(packedProperties, flw_material);
    flw_fragColor = vec4(1.0);
    flw_fragLight = flw_vertexLight;
    flw_shaderLight();
    flw_fragLight.x = max(flw_fragLight.x, _flw_dynamicBlockLight(flw_vertexPos.xyz) * (1.0 / 16.0));
    flw_vertexLight = flw_fragLight;
    _flw_guestAo = _flw_appliedAo;
}
