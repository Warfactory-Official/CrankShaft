package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

public final class StandardMaterialShaders {
    public static final MaterialShaders DEFAULT = new SimpleMaterialShaders(
            ResourceUtil.rl("material/default.vert"), ResourceUtil.rl("material/default.frag"));

    public static final MaterialShaders WIREFRAME = new SimpleMaterialShaders(
            ResourceUtil.rl("material/wireframe.vert"), ResourceUtil.rl("material/wireframe.frag"));

    public static final MaterialShaders LINE = new SimpleMaterialShaders(ResourceUtil.rl("material/lines.vert"),
            ResourceUtil.rl("material/lines.frag"));

    public static final MaterialShaders GLINT = new SimpleMaterialShaders(ResourceUtil.rl("material/glint.vert"),
            ResourceUtil.rl("material/default.frag"));

    // 26.2: vanilla glints at three UV densities (TextureTransform: item 8x / entity 0.5x / armor 0.16x);
    // upstream Flywheel only carries the item scale.
    public static final MaterialShaders GLINT_ENTITY = new SimpleMaterialShaders(
            ResourceUtil.rl("material/glint_entity.vert"),
            ResourceUtil.rl("material/default.frag"));

    public static final MaterialShaders GLINT_ARMOR = new SimpleMaterialShaders(
            ResourceUtil.rl("material/glint_armor.vert"),
            ResourceUtil.rl("material/default.frag"));

    private StandardMaterialShaders() {
    }
}
