bool flw_discardPredicate(vec4 color) {
    if (_flw_clipData.x > _flw_clipData.y) {
        return true;
    }
    return color.a < 0.5;
}
