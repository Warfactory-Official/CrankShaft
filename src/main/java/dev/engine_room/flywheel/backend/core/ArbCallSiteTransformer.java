package dev.engine_room.flywheel.backend.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

public final class ArbCallSiteTransformer {

    private static final String ARB_SHADER_OBJECTS = "org/lwjgl/opengl/ARBShaderObjects";
    private static final String ARB_VBO = "org/lwjgl/opengl/ARBVertexBufferObject";
    private static final String USE_PROGRAM = "glUseProgramObjectARB";
    private static final String USE_PROGRAM_DESC = "(I)V";
    private static final String BIND_BUFFER = "glBindBufferARB";
    private static final String BIND_BUFFER_DESC = "(II)V";

    private static final String TRACKER = "dev/engine_room/flywheel/backend/gl/GlStateTracker";
    private static final String TRACKER_DOT = "dev.engine_room.flywheel.backend.gl.GlStateTracker";
    private static final String WRAP_USE_PROGRAM = "arbUseProgram";
    private static final String WRAP_BIND_BUFFER = "arbBindBuffer";

    private ArbCallSiteTransformer() {
    }

    public static byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName.startsWith("org.lwjgl.") || TRACKER_DOT.equals(transformedName)) {
            return basicClass;
        }
        // Cheap pre-filter: skip classes whose constant pool doesn't even contain the ARB method
        // names. ASM's tree pass is fast, but most loaded classes touch neither — a String.indexOf
        // scan (intrinsified) saves the parse cost. ISO_8859_1 = 1:1 byte→char, so ASCII method
        // names survive verbatim.
        String pool = new String(basicClass, StandardCharsets.ISO_8859_1);
        if (!pool.contains(USE_PROGRAM) && !pool.contains(BIND_BUFFER)) {
            return basicClass;
        }

        ClassReader cr = new ClassReader(basicClass);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean changed = false;
        for (MethodNode method : cn.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.INVOKESTATIC || !(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (ARB_SHADER_OBJECTS.equals(call.owner)
                        && USE_PROGRAM.equals(call.name)
                        && USE_PROGRAM_DESC.equals(call.desc)) {
                    call.owner = TRACKER;
                    call.name = WRAP_USE_PROGRAM;
                    changed = true;
                } else if (ARB_VBO.equals(call.owner)
                        && BIND_BUFFER.equals(call.name)
                        && BIND_BUFFER_DESC.equals(call.desc)) {
                    call.owner = TRACKER;
                    call.name = WRAP_BIND_BUFFER;
                    changed = true;
                }
            }
        }

        if (!changed) return basicClass;

        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
