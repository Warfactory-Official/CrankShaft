package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

public class MatrixBuffer {
    private final ResizableStorageArray matrices = new ResizableStorageArray(EnvironmentStorage.MATRIX_SIZE_BYTES);

    public void flush(StagingBuffer stagingBuffer, EnvironmentStorage environmentStorage) {
        var arena = environmentStorage.arena;
        var capacity = arena.capacity();

        if (capacity == 0) {
            return;
        }

        matrices.ensureCapacity(capacity);

        stagingBuffer.enqueueCopy(arena.byteCapacity(), matrices.handle(), 0, ptr -> {
            MemoryUtil.memCopy(arena.indexToPointer(0), ptr, arena.byteCapacity());
        });
    }

    public void bind() {
        if (matrices.capacity() == 0) {
            return;
        }

        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.MATRICES, matrices.handle(), 0,
                matrices.byteCapacity());
    }

    public void delete() {
        matrices.delete();
    }
}
