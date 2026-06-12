package dev.engine_room.flywheel.lib.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ShadersModHelper {

    private ShadersModHelper() {
    }

    public static boolean isShaderPackInUse() {
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = irisApiClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance != null) {
                Method isShaderPackInUse = irisApiClass.getMethod("isShaderPackInUse");
                return (Boolean) isShaderPackInUse.invoke(instance);
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
            Field shaderPackLoadedField = shadersClass.getDeclaredField("shaderPackLoaded");
            shaderPackLoadedField.setAccessible(true);
            return shaderPackLoadedField.getBoolean(null);
        } catch (Throwable ignored) {
        }

        return false;
    }
}
