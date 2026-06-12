package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;

final class VkMlabOitChain extends VkPackedInsertOitChain {
    VkMlabOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        super(m, framebuffer, OitInsertMode.MLAB);
    }
}
