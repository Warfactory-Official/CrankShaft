package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.SundialTranslucent;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared pack-role selection without initializing either Iris renderer API.
 */
final class GuestProgramResolver {
    private static final Pattern MC_ENTITY = Pattern.compile("\\bmc_Entity\\b");

    private static final BlendModeOverride ENTITY_TRANSLUCENT_BLEND = new BlendModeOverride(new BlendMode(
            BlendModeFunction.SRC_ALPHA.getGlId(), BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId(),
            BlendModeFunction.ONE.getGlId(), BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId()));
    private static final BlendModeOverride GLINT_BLEND = new BlendModeOverride(new BlendMode(
            GL11C.GL_SRC_COLOR, GL11C.GL_ONE, GL11C.GL_ZERO, GL11C.GL_ONE));
    private static final Identifier GRAYSCALE_FONT = ResourceUtil.rl("material/nametag.frag");

    private GuestProgramResolver() {
    }

    static @Nullable ContractProperties contractProperties(IrisRenderingPipeline pipeline) {
        return contractSet(pipeline).flywheel$contractSource(ContractProgram.GBUFFERS) == null ? null
                : ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack().flywheel$contractProperties();
    }

    static @Nullable ContractProperties oitProperties(IrisRenderingPipeline pipeline, boolean shadow) {
        ContractProperties authored = ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack()
                                                                                .flywheel$contractProperties();
        if (authored.oit(shadow).explicitlyDisabled()) return authored;
        if (!shadow) {
            ContractProperties nativeOit = contractSet(pipeline).flywheel$nativeOit();
            if (nativeOit != null) return nativeOit;
        }
        return contractProperties(pipeline);
    }

    static boolean depthWrite(IrisRenderingPipeline pipeline, ProgramKey program, boolean requested) {
        var pack = ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack();
        PackRole role = program.role();
        boolean gbuffer = (role == PackRole.ADDITIVE || role.blockRole() == PackRole.TRANSLUCENT)
                && (role == PackRole.ADDITIVE ? pack.flywheel$deferredEmissive() : pack.flywheel$deferredTranslucent()
                && !forwardUnlit(contractSet(pipeline), program));
        return role.shadow || gbuffer || role == PackRole.ENTITIES || requested;
    }

    private static ContractProgramSet contractSet(IrisRenderingPipeline pipeline) {
        return ((ProgramFallbackResolverAccessor) ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()).flywheel$programs();
    }

    static Resolved resolve(IrisRenderingPipeline pipeline, ProgramKey key) {
        ProgramSource contract = contractSource(pipeline, key);
        if (contract == null) {
            if (key.oit() != null) {
                throw new IllegalStateException("OIT producer without a contract translucent program");
            }
            return new Resolved(key.forNative(), null, nativeSource(pipeline, key.role()));
        }
        ContractProgram id = Objects.requireNonNull(ContractProgram.byName(contract.getName()));
        return new Resolved(key.forContract(), id, contract);
    }

    static @Nullable ProgramSource contractSource(IrisRenderingPipeline pipeline, ProgramKey key) {
        ContractProgramSet contractSet = contractSet(pipeline);
        ContractProgram wanted = forwardUnlit(contractSet, key) ? ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT
                : ContractProgram.of(key.role(), Objects.requireNonNull(key.transparency()),
                contractSet::flywheel$hasContract);
        ProgramSource contract = key.role() == PackRole.EYES || key.role() == PackRole.ENTITIES_TRANSLUCENT
                || key.role() == PackRole.ENTITIES
                || (key.role() == PackRole.ADDITIVE || key.role() == PackRole.GLINT)
                && !contractSet.flywheel$hasContract(wanted) ? null : contractSet.flywheel$contractSource(wanted);
        if (contract != null && (contract.getTessControlSource()
                                         .isPresent() || contract.getTessEvalSource()
                                                                 .isPresent())) {
            FlwBackend.LOGGER.warn("{} has tessellation stages; drawing through the pack's own programs",
                    contract.getName());
            contract = null;
        }
        if (contract != null && key.role() == PackRole.TERRAIN && !readsMcEntity(contract)) {
            FlwBackend.LOGGER.info("{} ignores mc_Entity; borrowed block draws take the pack's terrain program",
                    contract.getName());
            contract = null;
        }
        return contract;
    }

    static boolean forwardUnlit(ContractProgramSet contracts, ProgramKey key) {
        return key.unlit() && key.role().blockRole() == PackRole.TRANSLUCENT
                && FogShaders.NONE.source().equals(key.fog())
                && contracts.flywheel$hasContract(ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT);
    }

    private static boolean readsMcEntity(ProgramSource source) {
        return source.getVertexSource()
                     .map(vertex -> MC_ENTITY.matcher(vertex)
                                             .find())
                     .orElse(false);
    }

    private static ProgramSource nativeSource(IrisRenderingPipeline pipeline, PackRole role) {
        if (role == PackRole.ADDITIVE) {
            ProgramSource beam = resolveExactly(pipeline, ProgramId.BeaconBeam);
            if (beam != null && (((IrisRenderingPipelineAccessor) pipeline).flywheel$pack().flywheel$deferredEmissive()
                    || writesColour(beam))) {
                return beam;
            }
            ProgramSource lightning = resolveExactly(pipeline, ProgramId.Lightning);
            if (lightning != null) {
                return lightning;
            }
            if (beam != null) {
                return beam;
            }
        }
        ProgramSource source = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()
                                                                         .resolveNullable(role.programId);
        if (source == null) {
            throw new BackendUnavailableException("Shaderpack has no program for " + role.programId);
        }
        return source;
    }

    private static @Nullable ProgramSource resolveExactly(IrisRenderingPipeline pipeline, ProgramId id) {
        ProgramSource source = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()
                                                                         .resolveNullable(id);
        return source != null && source.getName()
                                       .equals(id.getSourceName()) ? source : null;
    }

    private static boolean writesColour(ProgramSource source) {
        for (int buffer : source.getDirectives()
                                .getDrawBuffers()) {
            if (buffer == 0) {
                return true;
            }
        }
        return false;
    }

    static State state(IrisRenderingPipeline pipeline, ProgramKey key, @Nullable ContractProgram contract,
                       ProgramSource source) {
        AlphaTest alpha = contract != null ? AlphaTests.OFF : source.getDirectives()
                                                                    .getAlphaTestOverride()
                                                                    .orElse(key.role() == PackRole.GLINT
                                                                            ? ShaderKey.GLINT.getAlphaTest() : key.alphaTest());
        boolean nativeAdapter = source.getFragmentSource().orElseThrow().contains(ContractPatches.NATIVE_TRANSLUCENT);
        boolean nativeShadowAdapter = source.getFragmentSource().orElseThrow()
                                            .contains(ContractPatches.NATIVE_SHADOW_TRANSLUCENT);
        if (contract != null && source.getFragmentSource().orElseThrow().contains(SundialTranslucent.MARKER)) {
            alpha = AlphaTests.OFF;
        }
        if (contract != null && nativeAdapter) {
            alpha = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver().resolveNullable(ProgramId.Water)
                                                              .getDirectives().getAlphaTestOverride()
                                                              .orElse(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT.getAlphaTest());
        }
        if (contract != null && (nativeShadowAdapter || source.getFragmentSource().orElseThrow()
                                                              .contains(SundialTranslucent.SHADOW_MARKER))) {
            alpha = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()
                                                              .resolveNullable(ProgramId.ShadowWater).getDirectives()
                                                              .getAlphaTestOverride()
                                                              .orElse(ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT.getAlphaTest());
        }
        boolean shadow = key.role().shadow;
        List<BufferBlendInformation> bufferBlendInformation;
        BlendModeOverride blend;
        if (nativeAdapter || nativeShadowAdapter) {
            ProgramSource nativeWater = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver().resolveNullable(
                    nativeShadowAdapter ? ProgramId.ShadowWater : ProgramId.Water);
            bufferBlendInformation = nativeWater.getDirectives().getBufferBlendOverrides();
            blend = nativeWater.getDirectives().getBlendModeOverride()
                               .orElse(nativeShadowAdapter ? BlendModeOverride.OFF : null);
        } else if (contract != null) {
            ContractProperties properties = ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack()
                                                                                      .flywheel$contractProperties();
            bufferBlendInformation = properties.bufferBlend(contract);
            blend = properties.blend(contract);
            if (blend == null && shadow) {
                blend = BlendModeOverride.OFF;
            }
        } else {
            bufferBlendInformation = source.getDirectives()
                                           .getBufferBlendOverrides();
            blend = source.getDirectives()
                          .getBlendModeOverride()
                          .orElse(key.role() == PackRole.GLINT ? GLINT_BLEND
                                  : key.role() == PackRole.ENTITIES_TRANSLUCENT ? ENTITY_TRANSLUCENT_BLEND
                                  : key.role().programId.getBlendModeOverride());
        }
        if (blend == null && key.role() == PackRole.ADDITIVE && ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack()
                                                                                                          .flywheel$deferredEmissive()) {
            blend = BlendModeOverride.OFF;
        }
        boolean grayscaleFont = GRAYSCALE_FONT.equals(key.materialFragment()) && (contract == null
                || nativeAdapter || nativeShadowAdapter
                || source.getFragmentSource().orElseThrow().contains(ContractPatches.NATIVE_ADDITIVE));
        return new State(alpha, blend, bufferBlendInformation, grayscaleFont);
    }

    record State(AlphaTest alpha, @Nullable BlendModeOverride blend, List<BufferBlendInformation> bufferBlends,
                 boolean grayscale) {
    }

    record Resolved(ProgramKey program, @Nullable ContractProgram contract, ProgramSource source) {
    }
}
