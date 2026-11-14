package dev.engine_room.flywheel.backend.engine.indirect;

public final class BufferBindings {
    public static final int PAGE_FRAME_DESCRIPTOR = 0;
    public static final int INSTANCE = 1;
    public static final int DRAW_INSTANCE_INDEX = 2;
    public static final int MODEL = 3;
    public static final int DRAW = 4;

    public static final int LIGHT_LUT = 5;
    public static final int LIGHT_SECTION = 6;
    public static final int MATRICES = 7;

    // Ordered compaction only; the apply and draw after it rebind these slots.
    public static final int ORDERED_MODEL = 1;
    public static final int ORDERED_PAGE = 4;
    public static final int VIS_WORDS = 6;

    // Shaderpack guest draws only (motion vectors): the previous frame's instances and embedding poses, and a bit
    // per object uint set at the first uint of each instance written since then.
    public static final int HISTORY_INSTANCE = 10;
    public static final int HISTORY_MATRICES = 11;
    public static final int HISTORY_MOVED = 12;

    private BufferBindings() {
    }
}
