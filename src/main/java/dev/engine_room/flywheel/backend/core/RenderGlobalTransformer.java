package dev.engine_room.flywheel.backend.core;

import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class RenderGlobalTransformer {

    public static final String TARGET = "net.minecraft.client.renderer.RenderGlobal";

    private static final boolean DEOBF = FMLLaunchHandler.isDeobfuscatedEnvironment();
    private static final String RENDER_ENTITIES = DEOBF ? "renderEntities" : "func_180446_a";
    private static final String GET_TILE_ENTITY = DEOBF ? "getTileEntity" : "func_175625_s";

    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final String TILE_ENTITY = "net/minecraft/tileentity/TileEntity";
    private static final String ITERATOR = "java/util/Iterator";
    private static final String WORLD_CLIENT = "net/minecraft/client/multiplayer/WorldClient";
    private static final String GET_TILE_ENTITY_DESC = "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/tileentity/TileEntity;";
    private static final String HELPER = "dev/engine_room/flywheel/lib/visualization/VisualizationHelper";
    private static final String SHOULD_SKIP_ENTITY = "shouldSkipEntity";
    private static final String SHOULD_SKIP_TILE = "shouldSkipTileEntity";
    private static final String SHOULD_SKIP_ENTITY_DESC = "(Lnet/minecraft/entity/Entity;)Z";
    private static final String SHOULD_SKIP_TILE_DESC = "(Lnet/minecraft/tileentity/TileEntity;)Z";

    private static final int EXPECTED_ENTITY_FOR_EACH = 2;
    private static final int EXPECTED_TE_FOR_EACH = 2;
    private static final int EXPECTED_DAMAGE_GUARDS = 1;

    private RenderGlobalTransformer() {}

    public static byte[] transform(String name, String transformedName, byte[] basicClass) {
        ClassReader cr = new ClassReader(basicClass);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        int entityCount = 0;
        int tileEntityCount = 0;
        int damageCount = 0;
        for (MethodNode method : cn.methods) {
            if (RENDER_ENTITIES.equals(method.name)) {
                int[] counts = insertLoopGuards(method);
                entityCount += counts[0];
                tileEntityCount += counts[1];
                damageCount += counts[2];
            }
        }

        if (entityCount != EXPECTED_ENTITY_FOR_EACH
                || tileEntityCount != EXPECTED_TE_FOR_EACH
                || damageCount != EXPECTED_DAMAGE_GUARDS) {
            throw new IllegalStateException(String.format(
                    "RenderGlobalLoopGuardTransformer: expected %d Entity + %d TileEntity for-each guards "
                            + "and %d damage guard in RenderGlobal.renderEntities; inserted %d + %d + %d "
                            + "— pattern is stale",
                    EXPECTED_ENTITY_FOR_EACH, EXPECTED_TE_FOR_EACH, EXPECTED_DAMAGE_GUARDS,
                    entityCount, tileEntityCount, damageCount));
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Returns {@code [entityForEachCount, tileEntityForEachCount, damageGuardCount]}. */
    private static int[] insertLoopGuards(MethodNode method) {
        int entityCount = 0;
        int tileEntityCount = 0;
        int damageCount = 0;
        InsnList insns = method.instructions;
        AbstractInsnNode insn = insns.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();

            String entityType = matchForEachAstore(insn);
            if (entityType != null) {
                LabelNode loopStart = findLoopStart(insn, insns);
                if (loopStart != null) {
                    int localIdx = ((VarInsnNode) insn).var;
                    boolean tile = TILE_ENTITY.equals(entityType);
                    insns.insert(insn, buildGuard(localIdx, tile, loopStart));
                    if (tile) tileEntityCount++; else entityCount++;
                }
            }

            // First getTileEntity only; later ones in the same iteration are chest-adjacency reassignments.
            if (damageCount == 0 && matchFirstGetTileEntity(insn)) {
                AbstractInsnNode astore = insn.getNext();
                if (astore instanceof VarInsnNode varInsn && astore.getOpcode() == Opcodes.ASTORE) {
                    LabelNode loopStart = findLoopStart(astore, insns);
                    if (loopStart != null) {
                        insns.insert(astore, buildGuard(varInsn.var, true, loopStart));
                        damageCount = 1;
                    }
                }
            }

            insn = next;
        }
        return new int[]{entityCount, tileEntityCount, damageCount};
    }

    private static InsnList buildGuard(int localIdx, boolean tile, LabelNode loopStart) {
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, localIdx));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                tile ? SHOULD_SKIP_TILE : SHOULD_SKIP_ENTITY,
                tile ? SHOULD_SKIP_TILE_DESC : SHOULD_SKIP_ENTITY_DESC,
                false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, loopStart));
        return guard;
    }

    private static boolean matchFirstGetTileEntity(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL || !(insn instanceof MethodInsnNode call)) {
            return false;
        }
        return WORLD_CLIENT.equals(call.owner)
                && GET_TILE_ENTITY.equals(call.name)
                && GET_TILE_ENTITY_DESC.equals(call.desc);
    }

    /** CHECKCAST descriptor of an {@code Iterator.next() + CHECKCAST Entity|TileEntity + ASTORE}
     *  for-each tail, else null. */
    private static @Nullable String matchForEachAstore(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.ASTORE || !(insn instanceof VarInsnNode)) {
            return null;
        }
        AbstractInsnNode prev = insn.getPrevious();
        if (!(prev instanceof TypeInsnNode checkcast) || checkcast.getOpcode() != Opcodes.CHECKCAST) {
            return null;
        }
        if (!ENTITY.equals(checkcast.desc) && !TILE_ENTITY.equals(checkcast.desc)) {
            return null;
        }
        AbstractInsnNode prevPrev = prev.getPrevious();
        if (!(prevPrev instanceof MethodInsnNode itrNext) || itrNext.getOpcode() != Opcodes.INVOKEINTERFACE) {
            return null;
        }
        if (!ITERATOR.equals(itrNext.owner) || !"next".equals(itrNext.name)) {
            return null;
        }
        return checkcast.desc;
    }

    /** First GOTO after {@code from} whose target is at-or-before {@code from} — the loop's
     *  continue label. Forward GOTOs (ternary/if-else merges) target later labels and are skipped. */
    private static @Nullable LabelNode findLoopStart(AbstractInsnNode from, InsnList insns) {
        int fromIdx = insns.indexOf(from);
        AbstractInsnNode insn = from.getNext();
        while (insn != null) {
            if (insn instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && insns.indexOf(jump.label) <= fromIdx) {
                return jump.label;
            }
            insn = insn.getNext();
        }
        return null;
    }
}
