package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.model.Model.ConfiguredMesh;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.model.BlockModels;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecartTNT;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 1.12.2 has no native overlay texture; {@code Materials.TNT_FLASH_OVERLAY} replicates vanilla's
 * second pass with LIGHTNING blend ({@code SRC_ALPHA, ONE}) and {@code depthTest = EQUAL}, painting
 * only the cargo's silhouette.
 */
public class TntMinecartVisual<T extends EntityMinecartTNT> extends MinecartVisual<T> {
    private static final RendererReloadCache<IBlockState, Model> FLASH_MODEL_CACHE =
            new RendererReloadCache<>(TntMinecartVisual::bakeFlashModel);

    private static final Matrix4f HIDDEN_POSE = new Matrix4f().scale(0F);

    @Nullable private TransformedInstance flashInstance;
    @Nullable private IBlockState flashState;

    public TntMinecartVisual(VisualizationContext ctx, T entity, float partialTick) {
        super(ctx, entity, partialTick);
    }

    @Override
    protected void updateContents(TransformedInstance contents, Matrix4f pose, float partialTick, int light) {
        int fuseTime = entity.getFuseTicks();

        if (fuseTime > -1 && (float) fuseTime - partialTick + 1.0F < 10.0F) {
            float f = 1.0F - ((float) fuseTime - partialTick + 1.0F) / 10.0F;
            f = MathHelper.clamp(f, 0.0F, 1.0F);
            f *= f;
            f *= f;
            pose.scale(1.0F + f * 0.3F);
        }

        boolean flashing = fuseTime > -1 && fuseTime / 5 % 2 == 0;
        ensureFlashInstance(entity.getDisplayTile());
        if (flashInstance != null) {
            if (flashing) {
                flashInstance.setTransform(pose);
                flashInstance.color(1F, 1F, 1F, 1F);
                flashInstance.light(light);
                flashInstance.setChanged();
            } else {
                flashInstance.setTransform(HIDDEN_POSE);
                flashInstance.setChanged();
            }
        }
    }

    private void ensureFlashInstance(IBlockState state) {
        if (flashInstance != null && state == flashState) return;
        if (flashInstance != null) {
            flashInstance.delete();
            flashInstance = null;
        }
        Model flashModel = FLASH_MODEL_CACHE.get(state);
        if (flashModel.meshes().isEmpty()) return;
        Instancer<TransformedInstance> instancer = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, flashModel);
        flashInstance = instancer.createInstance();
        flashInstance.overlay(OverlayTexture.NO_OVERLAY);
        flashState = state;
    }

    private static Model bakeFlashModel(IBlockState state) {
        Model cargo = BlockModels.get(state);
        List<ConfiguredMesh> entries = cargo.meshes();
        if (entries.isEmpty()) return cargo;
        Mesh mesh = entries.get(0).mesh();
        return new SingleMeshModel(mesh, Materials.TNT_FLASH_OVERLAY);
    }

    @Override
    protected void _delete() {
        super._delete();
        if (flashInstance != null) {
            flashInstance.delete();
            flashInstance = null;
        }
    }
}
