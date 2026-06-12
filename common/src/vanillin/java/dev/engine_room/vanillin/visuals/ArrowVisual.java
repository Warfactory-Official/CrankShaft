package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.projectile.ArrowModel;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ArrowVisual<T extends AbstractArrow> extends EntityModelVisual<T, ArrowRenderState> {
    private static final Map<Identifier, Material> MATERIALS = new ConcurrentHashMap<>();

    private final Function<ArrowRenderState, Identifier> texture;

    public ArrowVisual(VisualizationContext ctx, T entity, float partialTick,
                       Function<ArrowRenderState, Identifier> texture) {
        super(ctx, entity, partialTick, ModelLayers.ARROW, ArrowModel::new);
        this.texture = texture;
    }

    @Override
    protected Identifier texture(ArrowRenderState state) {
        return texture.apply(state);
    }

    @Override
    protected Material material(Identifier texture) {
        return MATERIALS.computeIfAbsent(texture, tex -> SimpleMaterial.builder()
                                                                       .cutout(CutoutShaders.ONE_TENTH)
                                                                       .mipmap(false)
                                                                       .texture(tex)
                                                                       .build());
    }

    @Override
    protected void setupRootPose(PoseStack pose, ArrowRenderState state) {
        pose.mulPose(Axis.YP.rotationDegrees(state.yRot - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(state.xRot));
    }
}
