package dev.engine_room.flywheel.lib.util;

import dev.engine_room.flywheel.api.Flywheel;
import net.minecraft.util.ResourceLocation;

public final class ResourceUtil {
    public static final char NAMESPACE_SEPARATOR = ':';
    public static final String DEFAULT_NAMESPACE = Flywheel.ID;
    /** Upstream Flywheel namespace. Vendored shaders use {@code #include "flywheel:..."}
     *  literally; we silently rewrite that to our {@link #DEFAULT_NAMESPACE} so the
     *  shader files stay byte-identical to upstream and re-vendoring is a copy-paste. */
    public static final String UPSTREAM_NAMESPACE = "flywheel";

    private ResourceUtil() {
    }

    public static ResourceLocation rl(String path) {
        return new ResourceLocation(DEFAULT_NAMESPACE, path);
    }

    /**
     * Same as {@link ResourceLocation#ResourceLocation(String)}, but defaults to Flywheel namespace.
     */
    public static ResourceLocation parseFlywheelDefault(String location) {
        String namespace = DEFAULT_NAMESPACE;
        String path = location;

        int i = location.indexOf(NAMESPACE_SEPARATOR);
        if (i >= 0) {
            path = location.substring(i + 1);
            if (i >= 1) {
                String parsed = location.substring(0, i);
                namespace = UPSTREAM_NAMESPACE.equals(parsed) ? DEFAULT_NAMESPACE : parsed;
            }
        }

        return new ResourceLocation(namespace, path);
    }

    public static String toDebugFileNameNoExtension(ResourceLocation resourceLocation) {
        var stringLoc = resourceLocation.getNamespace() + "_" + resourceLocation.getPath().replace('/', '_');
        int dot = stringLoc.lastIndexOf('.');
        return dot >= 0 ? stringLoc.substring(0, dot) : stringLoc;
    }
}
