package dev.engine_room.flywheel.lib.visual.component;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a stitched glyph atlas's {@link GpuTextureView} back to the {@link Identifier} it was registered under.
 */
public final class GlyphAtlases {
    private static final Map<GpuTextureView, Identifier> VIEW_TO_ID = new ConcurrentHashMap<>();

    private GlyphAtlases() {
    }

    /**
     * Called from the {@code GlyphStitcher} mixin as each atlas is registered.
     */
    public static void register(GpuTextureView view, Identifier id) {
        VIEW_TO_ID.put(view, id);
    }

    public static @Nullable Identifier lookup(GpuTextureView view) {
        return VIEW_TO_ID.get(view);
    }
}
