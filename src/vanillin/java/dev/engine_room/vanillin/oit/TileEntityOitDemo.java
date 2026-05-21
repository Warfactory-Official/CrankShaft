package dev.engine_room.vanillin.oit;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Stub TE keying {@link OitDemoVisual} and {@link OitDemoRenderer}. Holds no state — the
 * variant is read from the host block class + state. Shared by all four marker block types
 * (glass / glass_pane / stained_glass / stained_glass_pane).
 *
 * <p>Render bounding box is widened ±1 in every direction. Each marker renders only at its own
 * position, but the visual implements {@code ShaderLightVisual} and runs the SMOOTH light shader,
 * which trilinearly samples a 2×2×2 corner around each fragment via {@code flw_light}. When the
 * marker sits at a section edge, those corner reads cross into neighbor sections; if those
 * neighbors aren't uploaded to the GPU light LUT the sample lands in stale padding memory and
 * produces banded-noise artifacts that shift with the camera. The ±1 expansion forces the
 * {@code AbstractBlockEntityVisual.setSectionCollector} enumeration to register the 8 sections
 * the AO read might touch.
 */
public final class TileEntityOitDemo extends TileEntity {
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos.add(-1, -1, -1), pos.add(2, 2, 2));
    }
}
