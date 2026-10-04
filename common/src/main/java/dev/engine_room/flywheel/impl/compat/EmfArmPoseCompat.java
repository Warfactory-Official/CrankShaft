package dev.engine_room.flywheel.impl.compat;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

import java.util.function.Predicate;

final class EmfArmPoseCompat {
    private static final ThreadLocal<Arms> ARMS = ThreadLocal.withInitial(Arms::new);
    private static Predicate<LivingEntity> condition;
    private static boolean registered;

    private EmfArmPoseCompat() {
    }

    static void register(Predicate<LivingEntity> next) {
        condition = condition == null ? next : condition.or(next);
        if (registered) return;
        try {
            EMFAnimationApi.registerAnimationHook(new EMFAnimationApi.EMFAnimationHook() {
                @Override
                public boolean onAnimationStart(AnimationContext context, boolean cancelled) {
                    EMFModelPartRoot root = context.animatingModelRoot();
                    if (playerRoot(root) && !context.activeState().isFirstPersonHand()
                            && EMFAnimationApi.getCurrentEntity() instanceof LivingEntity entity
                            && condition.test(entity)) {
                        Arms arms = ARMS.get();
                        assert arms.root == null;
                        arms.root = root;
                        arms.left = root.getChild("left_arm");
                        arms.right = root.getChild("right_arm");
                        float headYaw = root.getChild("head").yRot;
                        // EMF packs can classify the vanilla bow action from these yaws; keep the item's own spread after animation.
                        arms.leftOffset = headYaw + 0.5F - arms.left.yRot;
                        arms.rightOffset = headYaw - 0.5F - arms.right.yRot;
                        arms.left.yRot += arms.leftOffset;
                        arms.right.yRot += arms.rightOffset;
                    }
                    return true;
                }

                @Override
                public void onAnimationEnd(AnimationContext context, boolean cancelled) {
                    if (!playerRoot(context.animatingModelRoot())) return;
                    Arms arms = ARMS.get();
                    if (arms.root != context.animatingModelRoot()) return;
                    arms.left.yRot -= arms.leftOffset;
                    arms.right.yRot -= arms.rightOffset;
                    arms.root = null;
                    arms.left = null;
                    arms.right = null;
                }
            });
            registered = true;
        } catch (Exception error) {
            throw new IllegalStateException("Could not register EMF arm pose hook", error);
        }
    }

    private static boolean playerRoot(EMFModelPartRoot root) {
        String name = root.modelName.getfileName();
        return name.equals("player") || name.equals("player_slim");
    }

    private static final class Arms {
        EMFModelPartRoot root;
        ModelPart left;
        ModelPart right;
        float leftOffset;
        float rightOffset;
    }
}
