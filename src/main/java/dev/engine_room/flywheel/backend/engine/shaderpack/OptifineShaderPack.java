package dev.engine_room.flywheel.backend.engine.shaderpack;

import net.optifine.shaders.IShaderPack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class OptifineShaderPack implements IShaderPack {
    private final IShaderPack delegate;
    private ContractPatches.Plan plan;

    private OptifineShaderPack(IShaderPack delegate, ContractPatches.Plan plan) {
        this.delegate = delegate;
        this.plan = plan;
    }

    static IShaderPack wrap(IShaderPack pack) {
        if (pack == null || pack instanceof OptifineShaderPack || pack.getName().equals("(internal)")) return pack;
        try (var sources = new GuestShaderPack(pack.getName())) {
            return new OptifineShaderPack(pack, ContractPatches.prepare(sources));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    ContractPatches.Plan plan() {
        return plan;
    }

    void restage() {
        try (var sources = new GuestShaderPack(getName())) {
            plan = ContractPatches.prepare(sources);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        var normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.equals(plan.prefix() + "colorwheel.properties")) return new ByteArrayInputStream(new byte[0]);
        var source = normalized.startsWith(plan.prefix()) ? plan.resources().get(normalized.substring(
                plan.prefix().length())) : null;
        return source == null ? delegate.getResourceAsStream(path) : new ByteArrayInputStream(
                source.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean hasDirectory(String path) {
        return delegate.hasDirectory(path);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
