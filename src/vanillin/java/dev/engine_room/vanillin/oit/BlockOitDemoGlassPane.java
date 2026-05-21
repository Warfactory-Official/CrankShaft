package dev.engine_room.vanillin.oit;

/**
 * Mirrors {@code minecraft:glass_pane} — marker for a Flywheel-rendered plain glass pane (no
 * neighbour connections; vanilla panes between two marker blocks won't auto-connect since markers
 * aren't pane-class blocks). Identical to {@link BlockOitDemoGlass} but a distinct type so
 * {@link OitDemoVisual}/{@link OitDemoRenderer} can route it to the vanilla pane model.
 */
public final class BlockOitDemoGlassPane extends BlockOitDemoGlass {
}
