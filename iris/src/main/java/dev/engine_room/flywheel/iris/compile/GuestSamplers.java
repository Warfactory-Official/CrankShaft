package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.BackendUnavailableException;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.TextureType;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class GuestSamplers implements SamplerHolder {
    private final ProgramSamplers.Builder delegate;
    private final String label;
    private int remaining;

    GuestSamplers(ProgramSamplers.Builder delegate, int remaining, String label) {
        this.delegate = delegate;
        this.remaining = remaining;
        this.label = label;
    }

    @Override
    public void addExternalSampler(int unit, String... names) {
        delegate.addExternalSampler(unit, names);
    }

    @Override
    public boolean hasSampler(String name) {
        return delegate.hasSampler(name);
    }

    @Override
    public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier,
                                     Supplier<GlSampler> sampler, String... names) {
        return delegate.addDefaultSampler(type, texture, notifier, sampler, names);
    }

    @Override
    public boolean addDynamicSampler(TextureType type, IntSupplier texture, Supplier<GlSampler> sampler,
                                     String... names) {
        requireUnit(names);
        boolean added = delegate.addDynamicSampler(type, texture, sampler, names);
        if (added) remaining--;
        return added;
    }

    @Override
    public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier,
                                     Supplier<GlSampler> sampler, String... names) {
        requireUnit(names);
        boolean added = delegate.addDynamicSampler(type, texture, notifier, sampler, names);
        if (added) remaining--;
        return added;
    }

    private void requireUnit(String[] names) {
        if (remaining > 0) return;
        for (String name : names) {
            if (delegate.hasSampler(name)) {
                throw new BackendUnavailableException(label + " exhausts texture units at " + name);
            }
        }
    }
}
