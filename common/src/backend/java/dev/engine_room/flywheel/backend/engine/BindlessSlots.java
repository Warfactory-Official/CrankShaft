package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.material.Material;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend-neutral bindless texture slot registry: permanent slots per (texture, blur, mipmap), written into the
 * draw command's {@code packedTexIndices} and realized by whichever table is active this session --
 * {@code VkBindlessTable} (descriptor array, set 1) or {@code GlBindlessTable} (ARB_bindless_texture handle SSBO).
 * Slots 1..15 are reserved for engine frame resources (used by the VK table); material slots start at 16 and are
 * never recycled. Render-thread only.
 */
public final class BindlessSlots {
    /**
     * Sanity ceiling on the registry; the VK table additionally clamps to the driver's UAB limits.
     */
    public static final int MAX_SLOTS = 65536;
    public static final int FIRST_MATERIAL_SLOT = 16;
    // Slot 0 is reserved (a draw command's packedTexIndices of 0 = "no slot"), so slots.getInt's 0 default
    // doubles as the not-yet-allocated marker.
    private static final Object2IntMap<Key> slots = new Object2IntOpenHashMap<>();
    private static final List<Key> keys = new ArrayList<>();
    private BindlessSlots() {
    }

    /**
     * The permanent slot for {@code material}'s texture + filter pair; allocates on first sight.
     */
    public static int slot(Material material) {
        Key key = new Key(material.texture(), material.blur(), material.mipmap());
        int s = slots.getInt(key);
        if (s == 0) {
            s = keys.size() + FIRST_MATERIAL_SLOT;
            if (s >= MAX_SLOTS) {
                throw new IllegalStateException("Bindless texture slot registry exhausted (" + MAX_SLOTS + " slots)");
            }
            slots.put(key, s);
            keys.add(key);
        }
        return s;
    }

    public static int count() {
        return keys.size();
    }

    public static Key key(int i) {
        return keys.get(i);
    }

    public record Key(Identifier texture, boolean blur, boolean mipmap) {
    }
}
