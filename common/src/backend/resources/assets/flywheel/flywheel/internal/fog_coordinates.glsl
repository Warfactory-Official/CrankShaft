vec2 flw_fogDistances(vec3 viewPosition, mat4 modelView) {
    // World rendering composes camera rotation with render-origin translation. Undo the rotation
    // so cylindrical distance uses the world vertical axis and retains the camera-relative offset.
    vec3 cameraRelative = transpose(mat3(modelView)) * viewPosition;
    return vec2(length(viewPosition), max(length(cameraRelative.xz), abs(cameraRelative.y)));
}
