package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.util.function.LongConsumer;

public class LightBuffers {
    private final ResizableStorageArray lut = new ResizableStorageArray(4);
    private final ResizableStorageArray sections = new ResizableStorageArray(LightStorage.SECTION_SIZE_BYTES);
    private int[] copyWords;
    private int copySource, copyLength;
    private final LongConsumer copyWriter = ptr -> {
        for (int i = 0; i < copyLength; i++) {
            MemoryUtil.memPutInt(ptr + (long) i * Integer.BYTES, copyWords[copySource + i]);
        }
    };

    public void flush(StagingBuffer staging, LightStorage light) {
        var capacity = light.capacity();

        if (capacity == 0) {
            return;
        }

        sections.ensureCapacity(capacity);
        light.uploadChangedSections(staging, sections.handle());

        var update = light.pollLutUpdates();
        if (update != null) for (int span = 0; span < update.count(); span++) {
            this.lut.ensureCapacity(update.totalWords());
            copyWords = update.words();
            copySource = update.source(span);
            copyLength = update.length(span);
            staging.enqueueCopy((long) copyLength * Integer.BYTES, this.lut.handle(),
                    (long) update.offset(span) * Integer.BYTES, copyWriter);
        }
    }

    public void bind() {
        if (sections.capacity() == 0) {
            return;
        }

        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.LIGHT_LUT, lut.handle(), 0,
                lut.byteCapacity());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.LIGHT_SECTION, sections.handle(), 0,
                sections.byteCapacity());
    }

    public void delete() {
        lut.delete();
        sections.delete();
    }
}
