package dev.engine_room.flywheel.lib.util;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Animated atlas sprites baked into cached meshes. Texture animation that runs only for sprites seen in use (Sodium)
 * cannot see a cached mesh, so the engine re-marks every collected sprite once per frame until the next resource
 * reload. Mesh producers call {@link #add} while baking, from any thread.
 */
public final class AnimatedSprites {
    private static final Set<TextureAtlasSprite> SPRITES = ConcurrentHashMap.newKeySet();

    private AnimatedSprites() {
    }

    public static void add(TextureAtlasSprite sprite) {
        if (sprite.contents().isAnimated()) {
            SPRITES.add(sprite);
        }
    }

    @ApiStatus.Internal
    public static void forEach(Consumer<TextureAtlasSprite> action) {
        SPRITES.forEach(action);
    }

    @ApiStatus.Internal
    public static void clear() {
        SPRITES.clear();
    }
}
