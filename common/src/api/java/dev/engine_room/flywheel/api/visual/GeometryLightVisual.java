package dev.engine_room.flywheel.api.visual;

/**
 * Opts a visual into automatic compiled-terrain occluders on both loaders. Its tracked light sections
 * cover the receiving geometry; the engine additionally captures a one-section neighborhood around
 * them. Material light shaders still select geometry AO explicitly. Changes to tracked sections take
 * effect after visual updates, and section rebuild/unload edges refresh the captured geometry.
 * The halo supports a reach up to 16 blocks; longer-reach custom shaders must expand tracked sections.
 * Only opaque/cutout section meshes cast AO. This does not change terrain's own receiving shader.
 */
public interface GeometryLightVisual extends ShaderLightVisual {
}
