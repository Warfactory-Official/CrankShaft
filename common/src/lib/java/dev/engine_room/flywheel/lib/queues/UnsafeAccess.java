package dev.engine_room.flywheel.lib.queues;

import dev.engine_room.flywheel.lib.internal.TrustedLookupProvider;
import jdk.internal.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

final class UnsafeAccess {
    static final Unsafe UNSAFE;

    static {
        try {
            MethodHandle implAddExports = TrustedLookupProvider.IMPL_LOOKUP.findVirtual(Module.class, "implAddExports",
                    MethodType.methodType(void.class, String.class, Module.class));
            implAddExports.invokeExact(Object.class.getModule(), "jdk.internal.misc", UnsafeAccess.class.getModule());
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
        UNSAFE = Unsafe.getUnsafe();
    }

    private UnsafeAccess() {
    }
}
