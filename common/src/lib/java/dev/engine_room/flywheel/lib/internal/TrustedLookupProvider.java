package dev.engine_room.flywheel.lib.internal;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

@SuppressWarnings("removal")
public class TrustedLookupProvider {
    public static final MethodHandles.Lookup IMPL_LOOKUP;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            Field lookup = Class.forName("java.lang.invoke.MethodHandles$Lookup").getDeclaredField("IMPL_LOOKUP");
            IMPL_LOOKUP =  (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(lookup), unsafe.staticFieldOffset(lookup));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
