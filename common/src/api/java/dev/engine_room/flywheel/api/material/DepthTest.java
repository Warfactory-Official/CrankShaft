package dev.engine_room.flywheel.api.material;

import com.mojang.blaze3d.platform.CompareOp;

public enum DepthTest {
    OFF(CompareOp.ALWAYS_PASS),
    NEVER(CompareOp.NEVER_PASS),
    LESS(CompareOp.GREATER_THAN),
    EQUAL(CompareOp.EQUAL),
    LEQUAL(CompareOp.GREATER_THAN_OR_EQUAL),
    GREATER(CompareOp.LESS_THAN),
    NOTEQUAL(CompareOp.NOT_EQUAL),
    GEQUAL(CompareOp.LESS_THAN_OR_EQUAL),
    ALWAYS(CompareOp.ALWAYS_PASS);

    // 26.2 vanilla renders reversed-Z (default depth compare is GREATER_THAN_OR_EQUAL); this is the
    // reversed-Z CompareOp each conventional-Z test maps to.
    public final CompareOp compareOp;

    DepthTest(CompareOp compareOp) {
        this.compareOp = compareOp;
    }
}
