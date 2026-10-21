package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslPreprocessor;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.lib.material.FogShaders;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

final class ContractProgramSet {
    private static final Pattern OUTPUTS = Pattern.compile(
            "/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,\\s]+)\\*/");
    final ContractProperties properties;
    private final Map<ContractProgram, Source> sources = new EnumMap<>(ContractProgram.class);

    private ContractProgramSet(ContractProperties properties) {
        this.properties = properties;
    }

    static ContractProgramSet load(GuestHost host) {
        var plan = host.plan();
        try (var pack = plan == null ? new GuestShaderPack(host.packName()) : new GuestShaderPack(host.packName(),
                plan)) {
            if (plan == null) pack.overlay(ContractPatches.prepare(pack).resources());
            var result = new ContractProgramSet(new ContractProperties(pack.contract()));
            for (var program : ContractProgram.values()) {
                var candidates = host.sourceCandidates(pack, program.sourceName);
                for (var path : candidates) {
                    if (!pack.hasResource(path + ".vsh") || !pack.hasResource(path + ".fsh")) continue;
                    var vertex = GlslPreprocessor.preprocess(
                            host.preprocess(pack, path + ".vsh", program, GL20.GL_VERTEX_SHADER));
                    var fragment = GlslPreprocessor.preprocess(
                            host.preprocess(pack, path + ".fsh", program, GL20.GL_FRAGMENT_SHADER));
                    if (GlslAst.preprocessed(fragment).has(SundialTranslucent.MARKER))
                        fragment = SundialTranslucent.adapt(vertex, fragment);
                    if (GlslAst.preprocessed(vertex).has(SundialTranslucent.SHADOW_MARKER))
                        vertex = SundialTranslucent.shadowVertex(vertex, fragment);
                    var geometry = pack.hasResource(path + ".gsh") ? GlslPreprocessor.preprocess(
                            host.preprocess(pack, path + ".gsh", program, GL32.GL_GEOMETRY_SHADER)) : null;
                    var control = pack.hasResource(path + ".tcs") ? GlslPreprocessor.preprocess(
                            host.preprocess(pack, path + ".tcs", program, GL40.GL_TESS_CONTROL_SHADER)) : null;
                    var evaluation = pack.hasResource(path + ".tes") ? GlslPreprocessor.preprocess(
                            host.preprocess(pack, path + ".tes", program, GL40.GL_TESS_EVALUATION_SHADER)) : null;
                    if (control != null && evaluation == null)
                        throw new IllegalStateException("Colorwheel control shader has no evaluation shader: " + path);
                    result.sources.put(program, new Source(program, vertex, fragment, geometry, control, evaluation,
                            drawBuffers(fragment)));
                    break;
                }
            }
            FlwBackend.LOGGER.info("Colorwheel authored programs: {}", result.sources.keySet());
            return result;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static int[] drawBuffers(String fragment) {
        var matcher = OUTPUTS.matcher(fragment);
        int[] buffers = null;
        while (matcher.find()) {
            var value = matcher.group(2).replaceAll("\\s+", "");
            int[] declared = matcher.group(1).equals("DRAWBUFFERS") ? value.chars().map(c -> c - '0').toArray()
                    : Arrays.stream(value.split(",")).mapToInt(Integer::parseInt).toArray();
            // Packs append outputs inside active feature branches; the last active annotation owns the layout.
            buffers = declared;
        }
        return buffers == null ? new int[]{0} : buffers;
    }

    Source resolve(GuestRenderPass pass, Material material, boolean crumbling, int kind, boolean oit) {
        if (!pass.shadow && TaggedEnvironment.isRoutedKind(kind)) return null;
        var wanted = ContractProgram.of(material.transparency(), pass.shadow, crumbling);
        if (!pass.shadow && !crumbling && wanted == ContractProgram.GBUFFERS_TRANSLUCENT && !material.useLight()
                && material.fog().source().equals(FogShaders.NONE.source()) && sources.containsKey(
                ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT))
            wanted = ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT;
        if (!pass.shadow && !crumbling && wanted == ContractProgram.GBUFFERS_LIGHTNING && sources.containsKey(
                ContractProgram.GBUFFERS_NATIVE_ADDITIVE_COLOR))
            wanted = ContractProgram.GBUFFERS_NATIVE_ADDITIVE_COLOR;
        if (!pass.shadow && !crumbling) {
            if (material.transparency() == Transparency.OPAQUE) {
                if (kind == TaggedEnvironment.KIND_ENTITY) wanted = ContractProgram.GBUFFERS_ENTITIES;
                else if (kind == TaggedEnvironment.KIND_BORROWED_BLOCK) wanted = ContractProgram.GBUFFERS;
            } else if (!oit && material.transparency() == Transparency.TRANSLUCENT && kind == TaggedEnvironment.KIND_ENTITY
                    && sources.containsKey(ContractProgram.GBUFFERS_ENTITIES))
                wanted = ContractProgram.GBUFFERS_ENTITIES;
        }
        if (wanted == ContractProgram.GBUFFERS_GLINT && !sources.containsKey(wanted)) return null;
        if (wanted == ContractProgram.GBUFFERS_ADDITIVE && !sources.containsKey(wanted) && sources.containsKey(
                ContractProgram.GBUFFERS_LIGHTNING)) wanted = ContractProgram.GBUFFERS_LIGHTNING;
        else if (wanted == ContractProgram.GBUFFERS_LIGHTNING && !sources.containsKey(wanted) && sources.containsKey(
                ContractProgram.GBUFFERS_ADDITIVE)) wanted = ContractProgram.GBUFFERS_ADDITIVE;
        if ((wanted == ContractProgram.GBUFFERS_ADDITIVE || wanted == ContractProgram.GBUFFERS_LIGHTNING) && !sources.containsKey(
                wanted)) return null;
        for (var program = wanted; program != null; program = program.base) {
            var source = sources.get(program);
            if (source != null) {
                if (kind == TaggedEnvironment.KIND_BORROWED_BLOCK && material.transparency() == Transparency.OPAQUE
                        && source.program == ContractProgram.GBUFFERS && !source.readsMcEntity) return null;
                return source;
            }
        }
        return null;
    }

    boolean has(ContractProgram program) {
        return sources.containsKey(program);
    }

    boolean tessellated() {
        return sources.values().stream().anyMatch(source -> source.evaluation != null);
    }

    Source translucent(boolean shadow) {
        for (var program = shadow ? ContractProgram.SHADOW_TRANSLUCENT : ContractProgram.GBUFFERS_TRANSLUCENT;
             program != null; program = program.base) {
            var source = sources.get(program);
            if (source != null) return source;
        }
        return null;
    }

    static final class Source {
        final ContractProgram program;
        final String vertex;
        final String fragment;
        final String geometry;
        final String control;
        final String evaluation;
        final int[] drawBuffers;
        final boolean readsMcEntity;

        Source(ContractProgram program, String vertex, String fragment, String geometry, String control,
               String evaluation, int[] drawBuffers) {
            this.program = program;
            this.vertex = vertex;
            this.fragment = fragment;
            this.geometry = geometry;
            this.control = control;
            this.evaluation = evaluation;
            this.drawBuffers = drawBuffers;
            var ast = GlslAst.preprocessed(vertex);
            readsMcEntity = ast.references("mc_Entity") || ast.references("iris_Entity");
        }
    }
}
