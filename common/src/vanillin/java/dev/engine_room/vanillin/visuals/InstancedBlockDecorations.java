package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class InstancedBlockDecorations {
    private final Deco[] decos;
    private final Matrix4f scratch = new Matrix4f();

    InstancedBlockDecorations(InstancerProvider provider, LivingEntity entity, Map<String, Integer> boneIndex,
                              List<LivingEntityVisual.BlockDecoration> decorations) {
        List<Deco> list = new ArrayList<>();
        for (int di = 0; di < decorations.size(); di++) {
            LivingEntityVisual.BlockDecoration decoration = decorations.get(di);
            BlockState state = decoration.state()
                                         .apply(entity);
            Model model = Models.decorationBlock(state);
            for (LivingEntityVisual.BlockPlacement placement : decoration.placements()) {
                TransformedInstance instance = provider.instancer(InstanceTypes.TRANSFORMED, model)
                                                       .createInstance();
                list.add(new Deco(instance, LivingEntityVisual.resolveBonePath(placement.bone(), boneIndex),
                        placement.offset(), di));
            }
        }
        this.decos = list.toArray(new Deco[0]);
    }

    private static void setShown(Deco deco, boolean shown) {
        if (deco.shown != shown) {
            deco.instance.setVisible(shown);
            deco.shown = shown;
        }
    }

    // Reproduces ModelPart.translateAndRotate for one captured bone (the same math InstanceTree uses to pose bones).
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

    void apply(long conditionMask, int bitOffset, float[] transforms, Matrix4f root, int light, int overlayCoords) {
        for (Deco deco : decos) {
            if ((conditionMask & (1L << (bitOffset + deco.decorationIndex))) == 0) {
                setShown(deco, false);
                continue;
            }
            setShown(deco, true);
            scratch.set(root);
            for (int bone : deco.boneChain) {
                appendBoneLocal(scratch, transforms, bone);
            }
            scratch.mul(deco.offset);
            deco.instance.setTransform(scratch);
            deco.instance.light(light);
            deco.instance.overlay(overlayCoords);
            deco.instance.setChanged();
        }
    }

    void hide() {
        for (Deco deco : decos) {
            setShown(deco, false);
        }
    }

    void delete() {
        for (Deco deco : decos) {
            deco.instance.delete();
        }
    }

    private static final class Deco {
        final TransformedInstance instance;
        final int[] boneChain;
        final Matrix4fc offset;
        final int decorationIndex;
        boolean shown = true;

        Deco(TransformedInstance instance, int[] boneChain, Matrix4fc offset, int decorationIndex) {
            this.instance = instance;
            this.boneChain = boneChain;
            this.offset = offset;
            this.decorationIndex = decorationIndex;
        }
    }
}
