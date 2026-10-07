package dev.engine_room.flywheel.lib.util;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import net.minecraft.resources.Identifier;

import java.util.*;

/**
 * Shader inputs available before the first visual draw. Simple instance types and built simple materials register
 * automatically; custom implementations may register here during client initialization or resource reload.
 * Registration is thread-safe and performs no GPU work. The renderer snapshots the registry after resource apply
 * and when a shaderpack pipeline is created. Initialize lazily held instance types before resource apply finishes.
 */
public final class ShaderWarmupRegistry {
    private static final Set<InstanceType<?>> TYPES = new LinkedHashSet<>();
    // Materials may be resource-generation-specific or temporary; retaining them would retain obsolete textures.
    private static final Set<Material> MATERIALS = Collections.newSetFromMap(new WeakHashMap<>());

    private ShaderWarmupRegistry() {
    }

    public static synchronized void register(InstanceType<?> type) {
        TYPES.add(type);
    }

    public static synchronized void register(Material material) {
        MATERIALS.add(material);
    }

    public static synchronized List<InstanceType<?>> types() {
        return List.copyOf(TYPES);
    }

    /**
     * Shader and draw-state variants, independent of texture identity and runtime material property bits, in key order:
     * seeding assigns the cutout/fog indices baked into generated sources, which key cached program binaries.
     */
    public static synchronized List<Material> materials() {
        Map<Key, Material> variants = new TreeMap<>(Key.ORDER);
        for (Material material : MATERIALS) {
            variants.putIfAbsent(new Key(material), material);
        }
        return List.copyOf(variants.values());
    }

    private record Key(Identifier vertex, Identifier fragment, Identifier cutout, Identifier light, Identifier fog,
                       Transparency transparency, DepthTest depthTest, WriteMask writeMask, boolean cull,
                       boolean polygonOffset, boolean useLight) {
        private static final Comparator<Key> ORDER = Comparator.comparing(Key::vertex).thenComparing(Key::fragment)
                .thenComparing(Key::cutout).thenComparing(Key::light).thenComparing(Key::fog)
                .thenComparing(Key::transparency).thenComparing(Key::depthTest).thenComparing(Key::writeMask)
                .thenComparing(Key::cull).thenComparing(Key::polygonOffset).thenComparing(Key::useLight);

        private Key(Material material) {
            this(material.shaders().vertexSource(), material.shaders().fragmentSource(), material.cutout().source(),
                    material.light().source(), material.fog().source(), material.transparency(), material.depthTest(),
                    material.writeMask(), material.backfaceCulling(), material.polygonOffset(), material.useLight());
        }
    }
}
