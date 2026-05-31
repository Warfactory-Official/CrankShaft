package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.monster.EntityEnderman;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Block an enderman carries (vanilla {@code LayerHeldBlock}). The terminal {@code rotateY(90°)} reproduces
 * {@code renderModelBrightness}'s implicit block-quad rotation; the {@code (-0.5,-0.5,0.5)} X+Y flips are load-bearing.
 */
public final class EndermanHeldBlockLayer implements LivingLayer {
    private static final float ROT_X_20 = (float) Math.toRadians(20.0);
    private static final float ROT_Y_45 = (float) Math.toRadians(45.0);
    private static final float ROT_Y_90 = (float) Math.toRadians(90.0);

    private final EntityEnderman enderman;
    private final InstancerProvider instancers;
    private final int bias;
    private final Matrix4f scratch = new Matrix4f();

    @Nullable
    private IBlockState currentState;
    @Nullable
    private TransformedInstance instance;
    private boolean parentVisible = true;

    public EndermanHeldBlockLayer(VisualizationContext ctx, EntityEnderman enderman, int bias) {
        this.enderman = enderman;
        this.instancers = ctx.instancerProvider();
        this.bias = bias;
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        IBlockState state = parentVisible ? enderman.getHeldBlockState() : null;
        if (state == null) {
            clear();
            return;
        }

        if (instance == null || state != currentState) {
            if (instance != null) {
                instance.delete();
            }
            currentState = state;
            Model model = EntityMaterials.BLOCK_OVERLAY_MODELS.get(state);
            instance = instancers.instancer(InstanceTypes.TRANSFORMED, model, bias).createInstance();
        }

        scratch.set(rootPose)
                .translate(0.0F, 0.6875F, -0.75F)
                .rotateX(ROT_X_20)
                .rotateY(ROT_Y_45)
                .translate(0.25F, 0.1875F, 0.25F)
                .scale(-0.5F, -0.5F, 0.5F)
                .rotateY(ROT_Y_90);

        instance.setTransform(scratch);
        instance.light(light);
        // LayerHeldBlock.shouldCombineTextures() == false: never red-flashes.
        instance.overlay(OverlayTexture.NO_OVERLAY);
        instance.colorArgb(0xFFFFFFFF);
        instance.setChanged();
    }

    private void clear() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
        currentState = null;
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible) {
            clear();
        }
    }

    @Override
    public void delete() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
    }
}
