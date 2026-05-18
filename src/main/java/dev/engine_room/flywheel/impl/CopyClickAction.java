package dev.engine_room.flywheel.impl;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.event.ClickEvent;

// 1.12.2: ClickEvent.Action has no COPY_TO_CLIPBOARD. Pose as a RUN_COMMAND with a sentinel
// prefix and intercept in FlwEvents.onClientChat before it reaches the server.
public final class CopyClickAction {
    public static final String PREFIX = "$flw_copy:";

    private CopyClickAction() {
    }

    public static ClickEvent of(String value) {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, PREFIX + value);
    }

    public static boolean tryHandle(String msg) {
        if (!msg.startsWith(PREFIX)) {
            return false;
        }
        GuiScreen.setClipboardString(msg.substring(PREFIX.length()));
        return true;
    }
}
