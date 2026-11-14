package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

public class MatrixBuffer {
    private final ResizableStorageArray matrices = new ResizableStorageArray(EnvironmentStorage.MATRIX_SIZE_BYTES);
    // Same layout as matrices; only each pose is written.
    private final ResizableStorageArray previousPoses = new ResizableStorageArray(
            EnvironmentStorage.MATRIX_SIZE_BYTES);
    private boolean history;

    /**
     * {@code history}: also upload each embedding's previous-frame pose ({@link #bindHistory}).
     */
    public void flush(StagingBuffer stagingBuffer, EnvironmentStorage environmentStorage, boolean history) {
        var arena = environmentStorage.arena;
        var capacity = arena.capacity();
        this.history = history;

        if (capacity == 0) {
            return;
        }

        matrices.ensureCapacity(capacity);

        stagingBuffer.enqueueCopy(arena.byteCapacity(), matrices.handle(), 0, ptr -> {
            MemoryUtil.memCopy(arena.indexToPointer(0), ptr, arena.byteCapacity());
        });

        if (history) {
            previousPoses.ensureCapacity(capacity);
            stagingBuffer.enqueueCopy(arena.byteCapacity(), previousPoses.handle(), 0,
                    environmentStorage::writePreviousPoses);
        }
    }

    public void bind() {
        if (matrices.capacity() == 0) {
            return;
        }

        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.MATRICES, matrices.handle(), 0,
                matrices.byteCapacity());
    }

    /**
     * At {@link BufferBindings#HISTORY_MATRICES}; this frame's matrices when no history is kept.
     */
    public void bindHistory() {
        ResizableStorageArray source = history ? previousPoses : matrices;
        if (source.capacity() == 0) {
            return;
        }

        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.HISTORY_MATRICES, source.handle(), 0,
                source.byteCapacity());
    }

    public void delete() {
        matrices.delete();
        previousPoses.delete();
    }
}
