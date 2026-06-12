package dev.engine_room.flywheel.backend.compile;

public enum OitInsertMode {
    KBUFFER("_FLW_OIT_KBUFFER"),
    MLAB("_FLW_OIT_MLAB"),
    ABUFFER("_FLW_OIT_ABUFFER");

    public final String define;

    OitInsertMode(String define) {
        this.define = define;
    }

    public boolean needsInterlock() {
        return this == MLAB;
    }

    public boolean needsCounter() {
        return this == ABUFFER;
    }
}
