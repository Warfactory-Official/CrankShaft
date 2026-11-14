package dev.engine_room.flywheel.backend.engine.far;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * GPU-written buffer ranges handed to the CPU once the GPU has finished them, in request order, without stalling.
 * Render thread.
 */
final class FarReadback {
    private final ArrayDeque<Request> pending = new ArrayDeque<>();

    void request(int buffer, long offset, int size, Consumer<ByteBuffer> handler) {
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        int staging = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(staging, size, GL30C.GL_MAP_READ_BIT | GL44C.GL_CLIENT_STORAGE_BIT);
        GL45C.glCopyNamedBufferSubData(buffer, staging, offset, 0, size);
        pending.add(new Request(staging, GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0), size, handler));
    }

    void poll() {
        while (!pending.isEmpty()) {
            Request request = pending.peek();
            int status = GL32C.glClientWaitSync(request.fence, 0, 0);
            if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) {
                return;
            }
            pending.poll();
            ByteBuffer data = MemoryUtil.memAlloc(request.size);
            try {
                GL45C.glGetNamedBufferSubData(request.staging, 0, data);
                request.handler.accept(data);
            } finally {
                MemoryUtil.memFree(data);
                request.delete();
            }
        }
    }

    void delete() {
        pending.forEach(Request::delete);
        pending.clear();
    }

    private record Request(int staging, long fence, int size, Consumer<ByteBuffer> handler) {
        private void delete() {
            GL15C.glDeleteBuffers(staging);
            GL32C.glDeleteSync(fence);
        }
    }
}
