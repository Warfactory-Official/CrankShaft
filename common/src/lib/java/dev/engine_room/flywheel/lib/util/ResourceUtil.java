package dev.engine_room.flywheel.lib.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import dev.engine_room.flywheel.api.Flywheel;
import net.minecraft.IdentifierException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ResourceUtil {
    public static final char NAMESPACE_SEPARATOR = ':';
    public static final String DEFAULT_NAMESPACE = Flywheel.ID;
    /**
     * Upstream Flywheel namespace; silently rewritten to {@link #DEFAULT_NAMESPACE} so vendored shaders stay byte-identical to upstream.
     */
    public static final String UPSTREAM_NAMESPACE = "flywheel";
    private static final SimpleCommandExceptionType ERROR_INVALID = new SimpleCommandExceptionType(
            Component.translatable("argument.id.invalid"));

    private ResourceUtil() {
    }

    public static Identifier rl(String path) {
        return Identifier.fromNamespaceAndPath(DEFAULT_NAMESPACE, path);
    }

    public static Identifier parseFlywheelDefault(String location) {
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

        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /**
     * Same as {@code Identifier.read(StringReader)}, but defaults to the Flywheel namespace
     * (the {@code /flywheel backend <id>} argument resolves bare ids to {@code flywheel:*}).
     */
    public static Identifier readFlywheelDefault(StringReader reader) throws CommandSyntaxException {
        int i = reader.getCursor();

        while (reader.canRead() && isAllowedInId(reader.peek())) {
            reader.skip();
        }

        String s = reader.getString()
                         .substring(i, reader.getCursor());

        try {
            return parseFlywheelDefault(s);
        } catch (IdentifierException e) {
            reader.setCursor(i);
            throw ERROR_INVALID.createWithContext(reader);
        }
    }

    private static boolean isAllowedInId(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c == '_' || c == ':' || c == '/' || c == '.' || c == '-';
    }

    public static String toDebugFileNameNoExtension(Identifier resourceLocation) {
        var stringLoc = resourceLocation.getNamespace() + "_" + resourceLocation.getPath().replace('/', '_');
        int dot = stringLoc.lastIndexOf('.');
        return dot >= 0 ? stringLoc.substring(0, dot) : stringLoc;
    }
}
