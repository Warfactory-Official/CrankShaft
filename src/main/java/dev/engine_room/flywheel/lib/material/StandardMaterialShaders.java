package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

public final class StandardMaterialShaders {
    public static final MaterialShaders DEFAULT = new SimpleMaterialShaders(
            ResourceUtil.rl("material/default.vert"), ResourceUtil.rl("material/default.frag"));

    public static final MaterialShaders WIREFRAME = new SimpleMaterialShaders(ResourceUtil.rl("material/wireframe.vert"), ResourceUtil.rl("material/wireframe.frag"));

    public static final MaterialShaders LINE = new SimpleMaterialShaders(ResourceUtil.rl("material/lines.vert"), ResourceUtil.rl("material/lines.frag"));

    public static final MaterialShaders GLINT = new SimpleMaterialShaders(ResourceUtil.rl("material/glint.vert"), ResourceUtil.rl("material/default.frag"));

    // Second glint pass — vanilla 1.12.2 issues the glint mesh twice.
    public static final MaterialShaders GLINT_2 = new SimpleMaterialShaders(ResourceUtil.rl("material/glint2.vert"), ResourceUtil.rl("material/default.frag"));

    // Vanilla 1.12.2 ARMOR glint (LayerArmorBase.renderEnchantedGlint) — distinct texture matrix from the item glint.
    public static final MaterialShaders GLINT_ARMOR = new SimpleMaterialShaders(ResourceUtil.rl("material/glint_armor.vert"), ResourceUtil.rl("material/default.frag"));

    public static final MaterialShaders GLINT_ARMOR_2 = new SimpleMaterialShaders(ResourceUtil.rl("material/glint_armor2.vert"), ResourceUtil.rl("material/default.frag"));

    // TNT minecart flash overlay shader (1.12.2 parity).
    public static final MaterialShaders TNT_FLASH = new SimpleMaterialShaders(ResourceUtil.rl("material/default.vert"), ResourceUtil.rl("material/tnt_flash.frag"));

    private StandardMaterialShaders() {
    }
}
