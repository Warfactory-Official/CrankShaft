package dev.engine_room.vanillin.oit;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import net.minecraft.block.Block;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStainedGlassPane;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import org.joml.Matrix4f;

import java.util.function.Consumer;

/**
 * Flywheel-driven companion to the four OIT demo marker blocks
 * ({@link BlockOitDemoGlass} / {@link BlockOitDemoGlassPane} /
 * {@link BlockOitDemoStainedGlass} / {@link BlockOitDemoStainedGlassPane}). The marker block is
 * invisible to the chunk renderer; this visual renders the corresponding vanilla glass / glass
 * pane / stained_glass / stained_glass_pane model at the marker's own position via
 * {@link Models#block(IBlockState)}, which routes TRANSLUCENT-layer quads through
 * {@code Materials.TRANSLUCENT_BLOCK} ({@code ORDER_INDEPENDENT}) and exercises the OIT chain.
 */
public final class OitDemoVisual extends AbstractBlockEntityVisual<TileEntityOitDemo> implements ShaderLightVisual {
    private final TransformedInstance instance;
    private int packedLight;

    public OitDemoVisual(VisualizationContext ctx, TileEntityOitDemo te, float partialTick) {
        super(ctx, te, partialTick);

        IBlockState renderedState = resolveRenderedState(blockState.getBlock(), blockState);
        // ShaderLightVisual (marker interface above) tells the backend to upload this visual's
        // section to the GPU light LUT — that lets the chunk-style materials' SMOOTH light shader
        // call flw_light(pos, normal) at fragment time, returning real-world AO + smooth-lerped
        // lightmap. Models.block stays shared/cached across markers — AO is resolved at runtime
        // against the actual chunk neighborhood, no per-position bake required.
        instance = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.block(renderedState))
                .createInstance();
        instance.overlay(OverlayTexture.NO_OVERLAY);

        packedLight = computePackedLight();
        writePose();
    }

    private static IBlockState resolveRenderedState(Block markerBlock, IBlockState markerState) {
        if (markerBlock instanceof BlockOitDemoStainedGlass) {
            EnumDyeColor color = markerState.getValue(BlockOitDemoStainedGlass.COLOR);
            return Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockStainedGlass.COLOR, color);
        }
        if (markerBlock instanceof BlockOitDemoStainedGlassPane) {
            EnumDyeColor color = markerState.getValue(BlockOitDemoStainedGlassPane.COLOR);
            return Blocks.STAINED_GLASS_PANE.getDefaultState().withProperty(BlockStainedGlassPane.COLOR, color);
        }
        if (markerBlock instanceof BlockOitDemoGlassPane) {
            return Blocks.GLASS_PANE.getDefaultState();
        }
        return Blocks.GLASS.getDefaultState();
    }

    private void writePose() {
        instance.setTransform(new Matrix4f().translate(visualPos.getX(), visualPos.getY(), visualPos.getZ()))
                .light(packedLight)
                .setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        packedLight = computePackedLight();
        instance.light(packedLight).setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(instance);
    }
}
