package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

final class GuestSsbos {
    // Match the indirect instance, model, light and matrix bindings (0..7).
    private static final int OFFSET = 8;
    private static final int LIMIT = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
    private final GuestHost host;

    GuestSsbos(GuestHost host) {
        this.host = host;
    }

    static boolean supported() {
        for (var index : GuestHost.current().storageBindings()) if (index + OFFSET >= LIMIT) return false;
        return true;
    }

    private static List<Block> layout(int program) {
        var result = new ArrayList<Block>();
        var count = GL43.glGetProgramInterfacei(program, GL43.GL_SHADER_STORAGE_BLOCK, GL43.GL_ACTIVE_RESOURCES);
        try (var stack = MemoryStack.stackPush()) {
            var value = stack.mallocInt(1);
            for (var index = 0; index < count; index++) {
                var name = GL43.glGetProgramResourceName(program, GL43.GL_SHADER_STORAGE_BLOCK, index);
                if (name.startsWith("_Flw")) continue;
                GL43.glGetProgramResourceiv(program, GL43.GL_SHADER_STORAGE_BLOCK, index,
                        stack.ints(GL43.GL_BUFFER_BINDING), null, value);
                var binding = value.get(0);
                if (binding + OFFSET >= LIMIT) throw new UnsupportedOperationException(
                        "Shader-pack SSBO binding exceeds the indirect guest budget: " + binding);
                result.add(new Block(name, binding));
            }
        }
        return result;
    }

    void configure(GlProgram program) {
        if (!host.storageBindings().iterator().hasNext()) return;
        // Authored programs can own pack blocks absent from their native fallback program.
        for (var block : layout(program.handle())) {
            var index = GL43.glGetProgramResourceIndex(program.handle(), GL43.GL_SHADER_STORAGE_BLOCK, block.name);
            if (index >= 0) GL43.glShaderStorageBlockBinding(program.handle(), index, block.binding + OFFSET);
        }
    }

    void bind() {
        for (var index : host.storageBindings())
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index + OFFSET, host.storageHandle(index));
    }

    void restore() {
        host.restoreStorage();
    }

    private record Block(String name, int binding) {
    }
}
