package dev.engine_room.flywheel.lib.internal;

import dev.engine_room.flywheel.api.internal.DependencyInjection;

import java.lang.invoke.MethodHandles;

public interface TrustedLookupProvider {
    TrustedLookupProvider INSTANCE = DependencyInjection.load(TrustedLookupProvider.class,
            "dev.engine_room.flywheel.impl.TrustedLookupProviderImpl");

    MethodHandles.Lookup implLookup();
}
