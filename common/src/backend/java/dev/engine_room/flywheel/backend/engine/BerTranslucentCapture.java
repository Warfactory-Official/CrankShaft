package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.NoiseTextures;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class BerTranslucentCapture {
    // Master switch for M6d capture.
    private static final boolean ENABLED = true;

    @Nullable
    private static BerTranslucentCapture active;

    // Per-family draw lists (indexed by BerFamily.ordinal()): each family replays through its own producer
    // pipeline, so grouping at capture time keeps the replay's pipeline switches to one per family.
    private final List<CapturedDraw>[] draws;
    // Armed for the frame iff the engine OIT path can actually run (see beginFrame); enterCapturePhase only
    // installs as active() when armed.
    private boolean armed;

    @SuppressWarnings("unchecked")
    public BerTranslucentCapture() {
        draws = new List[BerFamily.VALUES.length];
        for (int i = 0; i < draws.length; i++) {
            draws[i] = new ArrayList<>();
        }
    }

    /**
     * The capture inside a captured {@code executeTranslucent} phase ({@code translucentModels},
     * {@code translucentCustomGeometry}, or {@code translucentBlocksAndItems}), or {@code null} outside them.
     */
    @Nullable
    public static BerTranslucentCapture active() {
        return active;
    }

    /**
     * The {@link BerFamily} whose OIT producer mirrors this RenderType, or {@code null} to fall through to the
     * normal vanilla draw. Gated on EXACT pipeline identity -- rendertypes sharing a vertex format but differing
     * in shading/cull/polygon-offset/output-target would replay through the wrong shader under a format-only
     * gate. {@code ENTITY_TRANSLUCENT} is also the translucent ENTITY-MODEL pipeline (slime jelly, horse armor
     * overlays, ...) -- the {@code translucentModels} phase is inside the capture window precisely so those
     * depth-writing main-target draws ride the OIT instead of occluding every translucent drawn after them (a
     * vanilla artifact on BOTH Fancy and Fabulous: entityTranslucent is not ITEM_ENTITY_TARGET-tagged).
     *
     * <p>Known fall-throughs (draw vanilla, by design): GLINT (a depth-EQUAL alpha-preserving re-shade of the
     * base draw -- when the base is captured the vanilla glint pass finds no matching depth, so foiled
     * translucent items lose the shimmer); non-blending "passenger" draws of a translucent-classified submit
     * (ENTITY_SOLID / ITEM_CUTOUT quads of mixed models); END_PORTAL/END_GATEWAY (not actually blending);
     * LIGHTNING; END_CRYSTAL_BEAM (despite the name it declares no {@code TRANSLUCENT} ColorTargetState -- a
     * cutout draw into the opaque target, so not order-dependent and not an OIT candidate); ARMOR_TRANSLUCENT
     * (only the wolf-armor damage overlay draws it: a PER_FACE_LIGHTING + lightmap + NO_OVERLAY + cutout
     * double-sided recipe matching no family -- a dedicated producer isn't justified for one rare layer, so it
     * takes the same self-occlusion tradeoff as the other fall-throughs); the NeoForge mod-facing pipelines (no
     * vanilla users).
     */
    @Nullable
    public static BerFamily familyOf(PreparedRenderType renderType) {
        var pipeline = renderType.pipeline();
        if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT) {
            return BerFamily.ENTITY;
        }
        // ITEM_TRANSLUCENT (core/item) and ENTITY_TRANSLUCENT_CULL (core/entity, plain branch) share one
        // coloring recipe: single minecraft_mix_light diffuse + lightmap + overlay + cutout 0.1 + cull ON.
        if (pipeline == RenderPipelines.ITEM_TRANSLUCENT || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL) {
            return BerFamily.ITEM;
        }
        if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE) {
            return BerFamily.ENTITY_EMISSIVE;
        }
        if (pipeline == RenderPipelines.TRANSLUCENT_BLOCK) {
            return BerFamily.MOVING_BLOCK;
        }
        if (pipeline == RenderPipelines.BEACON_BEAM_TRANSLUCENT) {
            return BerFamily.BEAM;
        }
        return null;
    }

    public void beginFrame() {
        clear();
        active = null;
        // SKIP_OIT (/flywheel debug oit off) disarms capture too, so vanilla draws translucent BEs normally while the
        // instance OIT is skipped -- a complete "OIT off" for benchmarking that doesn't suppress-then-lose BE geometry.
        armed = ENABLED && !BackendDebugFlags.SKIP_OIT && NoiseTextures.BLUE_NOISE != null;
    }

    /**
     * {@code executeTranslucent} RETURN: tear down (capture must never leak past the method).
     */
    public void endFrame() {
        active = null;
        armed = false;
    }

    public void enterCapturePhase() {
        if (armed) {
            active = this;
        }
    }

    /**
     * Captured-phase exit: stop capturing (the in-between phases must draw via vanilla).
     */
    public void exitCapturePhase() {
        active = null;
    }

    public boolean tryCapture(PreparedRenderType renderType, StagedVertexBuffer.ExecuteInfo info) {
        BerFamily family = familyOf(renderType);
        if (family == null) {
            return false;
        }
        draws[family.ordinal()].add(new CapturedDraw(renderType, info));
        return true;
    }

    public boolean isEmpty() {
        for (List<CapturedDraw> list : draws) {
            if (!list.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public List<CapturedDraw> draws(BerFamily family) {
        return draws[family.ordinal()];
    }

    public void clear() {
        for (List<CapturedDraw> list : draws) {
            list.clear();
        }
    }

    public record CapturedDraw(PreparedRenderType renderType, StagedVertexBuffer.ExecuteInfo info) {
    }
}
