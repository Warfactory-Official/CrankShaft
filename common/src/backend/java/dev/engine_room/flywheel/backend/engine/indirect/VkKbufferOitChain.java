package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;

final class VkKbufferOitChain extends VkPackedInsertOitChain {
    VkKbufferOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        super(m, framebuffer, OitInsertMode.KBUFFER);
    }
}
