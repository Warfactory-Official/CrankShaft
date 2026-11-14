package dev.engine_room.flywheel.lib.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jspecify.annotations.Nullable;

public final class ShadersModHelper {
    private static final MethodHandle PACK_IN_USE = shaderPackQuery();

    private ShadersModHelper() {
    }

    public static boolean isShaderPackInUse() {
        try {
            return (boolean) PACK_IN_USE.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Shader pack query failed", e);
        }
    }

    private static MethodHandle shaderPackQuery() {
        try {
            var irisApi = optionalClass("net.irisshaders.iris.api.v0.IrisApi");
            if (irisApi != null) {
                Object instance = irisApi.getMethod("getInstance").invoke(null);
                return MethodHandles.publicLookup()
                                    .findVirtual(irisApi, "isShaderPackInUse", MethodType.methodType(boolean.class))
                                    .bindTo(instance);
            }
            var shaders = optionalClass("net.optifine.shaders.Shaders");
            if (shaders != null) {
                var loaded = shaders.getDeclaredField("shaderPackLoaded");
                loaded.setAccessible(true);
                return MethodHandles.lookup().unreflectGetter(loaded);
            }
            return MethodHandles.constant(boolean.class, false);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static @Nullable Class<?> optionalClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException _) {
            return null;
        }
    }
}
