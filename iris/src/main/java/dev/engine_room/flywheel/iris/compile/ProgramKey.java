package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

record ProgramKey(PackRole role, boolean indirect, @Nullable InstanceType<?> type, Identifier materialVertex,
                  AlphaTest alphaTest, boolean embedded, boolean crumbling, int typeGen,
                  @Nullable Transparency transparency, @Nullable CutoutShader cutout,
                  @Nullable Identifier materialFragment, @Nullable Identifier light,
                  @Nullable LightSmoothness smoothness, @Nullable OitPass oit, @Nullable Identifier fog,
                  boolean unlit, GlslVersion glsl, boolean selector, boolean bindless, boolean velocity,
                  SortedBlendPass sortedBlendPass, boolean farLayer) {
    static ProgramKey of(PackRole role, boolean indirect, @Nullable InstanceType<?> type, Material material,
                         boolean embedded, boolean crumbling, int typeGen) {
        return new ProgramKey(role, indirect, type, material.shaders().vertexSource(),
                crumbling ? AlphaTests.OFF : cutoutAlphaTest(material.cutout()), embedded, crumbling, typeGen,
                ContractProgram.transparencyClass(material.transparency()), material.cutout(),
                material.shaders().fragmentSource(),
                material.light().source(),
                BackendConfig.INSTANCE.lightSmoothness(), null, material.fog().source(), !material.useLight(),
                GuestShaders.glslVersion(), !IrisRuntime.EXPERIMENTAL && !indirect && GlCompat.USE_INSTANCING_SELECTOR,
                false, false, SortedBlendPass.NORMAL, false);
    }

    /**
     * A cutout no Iris alpha test expresses, which {@code GuestShaders} tests where Iris would test one.
     */
    boolean cutoutPredicate() {
        return !crumbling && cutout != null && cutout != CutoutShaders.OFF && alphaTest == AlphaTests.OFF
                && cutout != CutoutShaders.CLIP_SLAB && cutout != CutoutShaders.CLIP_HALFSPACE;
    }

    private static AlphaTest cutoutAlphaTest(CutoutShader cutout) {
        if (cutout == CutoutShaders.EPSILON) {
            return AlphaTests.NON_ZERO_ALPHA;
        } else if (cutout == CutoutShaders.ONE_TENTH) {
            return AlphaTests.ONE_TENTH_ALPHA;
        } else if (cutout == CutoutShaders.HALF) {
            return AlphaTests.HALF_ALPHA;
        }
        // TODO: CLIP_SLAB/CLIP_HALFSPACE discard on _flw_clipData, which pack fragment stages never read.
        return AlphaTests.OFF;
    }

    ProgramKey withOit(OitPass pass) {
        return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                transparency, cutout, materialFragment, light, smoothness, pass, fog, unlit, glsl, selector, bindless,
                velocity, sortedBlendPass, farLayer);
    }

    /**
     * Albedo and its derived uniforms from the bindless table (GuestShaders); no {@code Sampler0} binding.
     */
    ProgramKey withBindless() {
        return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                transparency, cutout, materialFragment, light, smoothness, oit, fog, unlit, glsl, selector, true,
                velocity, sortedBlendPass, farLayer);
    }

    /**
     * The pack program reads {@code at_velocity}: draws bind the previous frame's instances (GuestShaders).
     */
    ProgramKey withVelocity() {
        return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                transparency, cutout, materialFragment, light, smoothness, oit, fog, unlit, glsl, selector, bindless,
                true, sortedBlendPass, farLayer);
    }

    ProgramKey forNative() {
        boolean vertexLight = !crumbling && role != PackRole.ADDITIVE;
        boolean readsLight = vertexLight && role != PackRole.GLINT;
        return new ProgramKey(role == PackRole.BLOCK_ENTITY ? PackRole.SOLID : role, indirect, type, materialVertex,
                alphaTest, embedded && (!indirect || vertexLight), crumbling, typeGen, null,
                cutoutPredicate() ? cutout : null,
                ResourceUtil.rl("material/nametag.frag").equals(materialFragment) ? materialFragment : null,
                readsLight ? light : null, readsLight ? smoothness : null, null, null,
                false, glsl, selector, bindless, velocity, sortedBlendPass, farLayer);
    }

    ProgramKey forContract() {
        return new ProgramKey(role == PackRole.TERRAIN_TRANSLUCENT ? role : role.blockRole(), indirect, type,
                materialVertex, AlphaTests.OFF, embedded, crumbling, typeGen,
                role == PackRole.ADDITIVE ? transparency : null, cutout, materialFragment, light, smoothness, oit,
                FogShaders.NONE.source().equals(fog) ? fog : null, false, glsl, selector, bindless, velocity,
                sortedBlendPass, farLayer);
    }

    ProgramKey withTypeGen(int typeGen) {
        return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                transparency, cutout, materialFragment, light, smoothness, oit, fog, unlit, glsl, selector, bindless,
                velocity, sortedBlendPass, farLayer);
    }

    ProgramKey withSortedBlendPass(SortedBlendPass pass) {
        return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                transparency, cutout, materialFragment, light, smoothness, oit, fog, unlit, glsl, selector, bindless,
                velocity, pass, farLayer);
    }

    /**
     * Retained far-layer hits instead of instances ({@link FarLayerMainGuest}): captured colour and light, no
     * albedo texture.
     */
    ProgramKey withFarLayer() {
        return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                transparency, cutout, materialFragment, light, smoothness, oit, fog, unlit, glsl, selector, bindless,
                velocity, sortedBlendPass, true);
    }

    String cacheName() {
        return (role.name() + (indirect ? "_indirect_" : "_instancing_")
                + (type == null ? "uber" + typeGen : ResourceUtil.toDebugFileNameNoExtension(type.vertexShader()))
                + "_" + ResourceUtil.toDebugFileNameNoExtension(materialVertex) + "_" + alphaTest.function() + "_"
                + alphaTest.reference() + (embedded ? "_embed" : "") + (crumbling ? "_crumbling" : "")
                + (materialFragment == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(materialFragment))
                + (light == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(light))
                + (cutout == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(cutout.source()))
                + (smoothness == null ? "" : "_" + smoothness.getSerializedName())
                + (fog == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(fog))
                + (unlit ? "_unlit" : "")
                + (oit == null ? "" : "_oit_" + oit.name())
                + (glsl == GlslVersion.V460 ? "" : "_glsl" + glsl.version)
                + (selector ? "_selector" : "")
                + (bindless ? "_bindless" : "")
                + (velocity ? "_velocity" : "")
                + (sortedBlendPass == SortedBlendPass.NORMAL ? "" : "_sorted_blend_" + sortedBlendPass.name())
                + (farLayer ? "_far_layer" : ""))
                .toLowerCase(Locale.ROOT);
    }

}
