package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.api.pipeline.pack.ShaderOption;
import com.luna.ausm.impl.pipeline.PipelineContext;
import com.luna.ausm.impl.pipeline.pack.ShaderOptionScanner;
import com.luna.ausm.impl.pipeline.pack.ShaderPack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class AusmShaderPack implements ShaderPack {
    private static final Set<String> CONTRACT_SOURCES = Arrays.stream(ContractProgram.values())
                                                              .map(program -> program.sourceName)
                                                              .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> SHADER_EXTENSIONS = Set.of(".vsh", ".fsh", ".gsh", ".glsl");
    private final ShaderPack delegate;
    private final ContractPatches.Plan plan;

    private AusmShaderPack(ShaderPack delegate, ContractPatches.Plan plan) {
        this.delegate = delegate;
        this.plan = plan;
    }

    public static ShaderPack forProperties(ShaderPack pack) {
        if (pack instanceof AusmShaderPack || pack.getName().equals("(internal)")) return pack;
        try (var sources = new GuestShaderPack(pack.getName())) {
            return new AusmShaderPack(pack, ContractPatches.prepare(sources));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static ShaderPack forCompilation(ShaderPack pack) {
        if (pack instanceof AusmShaderPack) return pack;
        var pipeline = PipelineContext.getInstance();
        if (!pack.getName().equals(pipeline.activePackName)) return pack;
        var plan = AusmContractShaderProperties.of(pipeline.shaderProperties).flywheel$patches();
        return withPlan(pack, plan);
    }

    public static ShaderPack withPlan(ShaderPack pack, ContractPatches.Plan plan) {
        return plan == null || pack instanceof AusmShaderPack ? pack : new AusmShaderPack(pack, plan);
    }

    public static void scanGuestOptions(ShaderPack pack, Set<String> sliders, Set<String> propertyOptions,
                                        Map<String, ShaderOption> options, Set<String> visited) {
        if (!(pack instanceof AusmShaderPack patched)) return;
        for (var path : patched.plan.resources().keySet().stream().sorted().toList()) {
            var filename = path.substring(path.lastIndexOf('/') + 1);
            var extension = filename.lastIndexOf('.');
            if (extension < 0 || !CONTRACT_SOURCES.contains(
                    filename.substring(0, extension)) || !SHADER_EXTENSIONS.contains(filename.substring(extension)))
                continue;
            ShaderOptionScanner.scanShaderFile(pack, patched.plan.prefix() + path, sliders, propertyOptions, options,
                    visited);
        }
    }

    public ContractPatches.Plan plan() {
        return plan;
    }

    private String override(String path) {
        // These describe guest accumulation targets, not native gbuffer formats/blending or terrain shadow enablement.
        if (path.equals(plan.prefix() + "colorwheel.properties")) return "";
        return path.startsWith(plan.prefix()) ? plan.resources().get(path.substring(plan.prefix().length())) : null;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public InputStream getResourceAsStream(String path) throws IOException {
        var source = override(path);
        return source == null ? delegate.getResourceAsStream(path) : new ByteArrayInputStream(
                source.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean hasResource(String path) {
        return override(path) != null || delegate.hasResource(path);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
