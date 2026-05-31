package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelHorse;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;

/** {@link EntityModel} for {@link ModelHorse} (horse / skeleton+zombie horse / donkey+mule). {@code chestHorse}
 *  selects mule ears and inserts the chest boxes before the tack. Needs the access transformer (fields are private). */
public final class HorseEntityModel implements EntityModel<ModelHorse> {
    private final boolean chestHorse;

    // Stashed by the visual's poseModel each frame: vanilla ModelHorse.render reads
    // grassEatingAmount(0) to blend the baby head group's translate while grazing.
    public float grassEatingAmount;

    public HorseEntityModel(boolean chestHorse) {
        this.chestHorse = chestHorse;
    }

    /** Index of the first tack root: 23 for chest horses, 21 otherwise. Reins are {@code tackStart()+10} and {@code +11}. */
    public int tackStart() {
        return chestHorse ? 23 : 21;
    }

    @Override
    public ModelHorse create() {
        return new ModelHorse();
    }

    @Override
    public ModelRenderer[] roots(ModelHorse m) {
        ModelRenderer leftEar = chestHorse ? m.muleLeftEar : m.horseLeftEar;
        ModelRenderer rightEar = chestHorse ? m.muleRightEar : m.horseRightEar;
        if (chestHorse) {
            return new ModelRenderer[] {
                    m.backLeftLeg, m.backLeftShin, m.backLeftHoof, m.backRightLeg, m.backRightShin, m.backRightHoof,
                    m.frontLeftLeg, m.frontLeftShin, m.frontLeftHoof, m.frontRightLeg, m.frontRightShin, m.frontRightHoof,
                    m.body, m.tailBase, m.tailMiddle, m.tailTip, m.neck, m.mane,
                    leftEar, rightEar, m.head, m.muleLeftChest, m.muleRightChest,
                    m.horseFaceRopes, m.horseSaddleBottom, m.horseSaddleFront, m.horseSaddleBack,
                    m.horseLeftSaddleRope, m.horseLeftSaddleMetal, m.horseRightSaddleRope, m.horseRightSaddleMetal,
                    m.horseLeftFaceMetal, m.horseRightFaceMetal, m.horseLeftRein, m.horseRightRein,
            };
        }
        return new ModelRenderer[] {
                m.backLeftLeg, m.backLeftShin, m.backLeftHoof, m.backRightLeg, m.backRightShin, m.backRightHoof,
                m.frontLeftLeg, m.frontLeftShin, m.frontLeftHoof, m.frontRightLeg, m.frontRightShin, m.frontRightHoof,
                m.body, m.tailBase, m.tailMiddle, m.tailTip, m.neck, m.mane,
                leftEar, rightEar, m.head,
                m.horseFaceRopes, m.horseSaddleBottom, m.horseSaddleFront, m.horseSaddleBack,
                m.horseLeftSaddleRope, m.horseLeftSaddleMetal, m.horseRightSaddleRope, m.horseRightSaddleMetal,
                m.horseLeftFaceMetal, m.horseRightFaceMetal, m.horseLeftRein, m.horseRightRein,
        };
    }

    @Override
    public boolean hasBabyTransform() {
        return true;
    }

    @Override
    public void babyTransform(Matrix4f dest, ModelHorse m, int rootIndex) {
        // Per ModelHorse.render with getHorseSize() == 0.5: legs (0-11), body/tail/neck/mane (12-17), then
        // ears+head; translates are raw block units (vanilla GL-translates them unscaled by the model scale).
        // Tack/chest roots (>= 21) only draw on adults (vanilla gates them on !isChild), so they take the
        // head group's transform moot-ly.
        if (rootIndex < 12) {
            dest.scale(0.5F, 0.75F, 0.5F).translate(0.0F, 0.475F, 0.0F);
        } else if (rootIndex < 18) {
            dest.scale(0.5F).translate(0.0F, 0.675F, 0.0F);
        } else {
            float f = grassEatingAmount;
            dest.scale(0.625F);
            if (f <= 0.0F) {
                dest.translate(0.0F, 0.675F, 0.0F);
            } else {
                dest.translate(0.0F, 0.45F * f + 0.675F * (1.0F - f), 0.075F * f);
            }
        }
    }
}
