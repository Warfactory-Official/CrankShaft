package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

final class InstancedDynamicBlocks {
    private final InstancerProvider provider;
    private final Slot[] slots;
    private final Matrix4f scratch = new Matrix4f();

    InstancedDynamicBlocks(InstancerProvider provider, int[][] boneChains, Matrix4fc[] offsets) {
        this.provider = provider;
        this.slots = new Slot[boneChains.length];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot(boneChains[i], offsets[i]);
        }
    }

    private static void hide(Slot slot) {
        if (slot.instance != null && slot.shown) {
            slot.instance.setVisible(false);
            slot.shown = false;
        }
    }

    private static void appendBoneLocal(Matrix4f matrix, float[] t, int boneIndex) {
        int b = boneIndex * 9;
        matrix.translate(t[b] / 16.0F, t[b + 1] / 16.0F, t[b + 2] / 16.0F);
        if (t[b + 3] != 0.0F || t[b + 4] != 0.0F || t[b + 5] != 0.0F) {
            matrix.rotateZYX(t[b + 5], t[b + 4], t[b + 3]);
        }
        if (t[b + 6] != 1.0F || t[b + 7] != 1.0F || t[b + 8] != 1.0F) {
            matrix.scale(t[b + 6], t[b + 7], t[b + 8]);
        }
    }

    void apply(@Nullable BlockState[] states, float[] transforms, Matrix4f root, int light, int overlayCoords) {
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            BlockState want = states[i];
            if (want == null) {
                hide(slot);
                continue;
            }
            if (slot.instance == null || !want.equals(slot.state)) {
                if (slot.instance != null) {
                    slot.instance.delete();
                }
                slot.instance = provider.instancer(InstanceTypes.TRANSFORMED, Models.decorationBlock(want))
                                        .createInstance();
                slot.state = want;
                slot.shown = true;
            } else if (!slot.shown) {
                // Reveal BEFORE the writes below: a hidden handle's setters go to the trash slot and the reveal
                // seeds the fresh slot with an identity pose.
                slot.instance.setVisible(true);
                slot.shown = true;
            }
            scratch.set(root);
            for (int bone : slot.boneChain) {
                appendBoneLocal(scratch, transforms, bone);
            }
            scratch.mul(slot.offset);
            slot.instance.setTransform(scratch);
            slot.instance.light(light);
            slot.instance.overlay(overlayCoords);
            slot.instance.setChanged();
        }
    }

    void hide() {
        for (Slot slot : slots) {
            hide(slot);
        }
    }

    void delete() {
        for (Slot slot : slots) {
            if (slot.instance != null) {
                slot.instance.delete();
                slot.instance = null;
            }
        }
    }

    private static final class Slot {
        final int[] boneChain;
        final Matrix4fc offset;
        @Nullable
        TransformedInstance instance;
        @Nullable
        BlockState state;
        boolean shown;

        Slot(int[] boneChain, Matrix4fc offset) {
            this.boneChain = boneChain;
            this.offset = offset;
        }
    }
}
