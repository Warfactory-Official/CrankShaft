package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Matrix4f;

/**
 * Concrete {@link AbstractLivingEntityVisual} for mobs that need no custom posing — just a model, a
 * texture, a shadow radius, an optional uniform {@code preRenderCallback} scale (husk 1.0625, wither
 * skeleton 1.2, cave spider 0.7, …), and an optional death-flop angle (spiders flop 180°).
 */
public class SimpleLivingEntityVisual<T extends EntityLivingBase, M extends ModelBase>
        extends AbstractLivingEntityVisual<T, M> {
    private final float uniformScale;
    private final float deathMaxRotation;
    private final boolean instancesBabies;

    public SimpleLivingEntityVisual(VisualizationContext ctx, T entity, float partialTick,
                                    EntityModel<M> model, Material material, String cacheKey,
                                    float shadowRadius, float uniformScale, float deathMaxRotation) {
        this(ctx, entity, partialTick, model, material, cacheKey, shadowRadius, uniformScale, deathMaxRotation, false);
    }

    public SimpleLivingEntityVisual(VisualizationContext ctx, T entity, float partialTick,
                                    EntityModel<M> model, Material material, String cacheKey,
                                    float shadowRadius, float uniformScale, float deathMaxRotation,
                                    boolean instancesBabies) {
        super(ctx, entity, partialTick, model, material, cacheKey, shadowRadius);
        this.uniformScale = uniformScale;
        this.deathMaxRotation = deathMaxRotation;
        this.instancesBabies = instancesBabies;
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        if (uniformScale != 1.0F) {
            dest.scale(uniformScale);
        }
    }

    @Override
    protected float getDeathMaxRotation() {
        return deathMaxRotation;
    }

    @Override
    protected boolean instancesBabies() {
        return instancesBabies;
    }
}
