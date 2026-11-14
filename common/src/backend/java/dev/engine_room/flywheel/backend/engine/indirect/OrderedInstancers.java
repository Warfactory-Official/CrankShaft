package dev.engine_room.flywheel.backend.engine.indirect;

import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The frame's {@link IndirectInstancer#ordered} instancers for the ordered compaction ({@code order.glsl}): a record
 * each and their page slots in page order, rewritten only when a slot or a list offset changes.
 */
final class OrderedInstancers {
    static final long RECORD_BYTES = 16;

    private final List<IndirectInstancer<?>> instancers = new ArrayList<>();
    private int pageCount;
    private int version;

    void begin() {
        instancers.clear();
        pageCount = 0;
    }

    /**
     * After the instancer's {@link IndirectInstancer#update} for this frame.
     */
    void add(IndirectInstancer<?> instancer) {
        if (!instancer.ordered) {
            return;
        }
        int slotVersion = instancer.mapping.slotVersion();
        if (instancer.orderedPageOffset != pageCount || instancer.orderedPageVersion != slotVersion) {
            instancer.orderedPageOffset = pageCount;
            instancer.orderedPageVersion = slotVersion;
            version++;
        }
        instancers.add(instancer);
        pageCount += instancer.mapping.pageCount();
    }

    int count() {
        return instancers.size();
    }

    int pageCount() {
        return pageCount;
    }

    /**
     * Changes whenever {@link #writePages} would write different contents.
     */
    int pageVersion() {
        return version;
    }

    void writeRecords(long ptr) {
        for (var instancer : instancers) {
            MemoryUtil.memPutInt(ptr, instancer.modelIndex());
            MemoryUtil.memPutInt(ptr + 4, instancer.orderedPageOffset);
            MemoryUtil.memPutInt(ptr + 8, instancer.mapping.pageCount());
            MemoryUtil.memPutInt(ptr + 12, instancer.strideUints());
            ptr += RECORD_BYTES;
        }
    }

    void writePages(long ptr) {
        for (var instancer : instancers) {
            var mapping = instancer.mapping;
            for (int page = 0; page < mapping.pageCount(); page++) {
                MemoryUtil.memPutInt(ptr, mapping.pageSlot(page));
                ptr += Integer.BYTES;
            }
        }
    }
}
