package dev.engine_room.flywheel.lib.util;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.lib.material.ShaderVariant;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

import java.util.*;

/**
 * Shader inputs available before the first visual draw. Simple instance types and built simple materials register
 * automatically; custom implementations, and materials built lazily on first use, register here during client
 * initialization or resource reload. Warm-up is keyed by {@link ShaderVariant}: one registration covers every
 * material of its variant whatever their depth test, depth write, culling, polygon offset or texture, so register
 * one material or variant per variant, not per state combination. A variant needs no texture or material instance,
 * e.g. {@code SimpleMaterial.builder()...variant()}. Registration is thread-safe and performs no GPU work. The
 * renderer snapshots the registry after resource apply and when a shaderpack pipeline is created, and compiles the
 * snapshot there. Initialize lazily held instance types before resource apply finishes.
 * <p>
 * A variant or instance type first drawn after the snapshot compiles in the background, and its draws are skipped
 * until it is built. Each such draw counts as a {@linkplain #misses() miss}, and the first miss of each variant is
 * logged. Draws of registered variants and types skipped for any other reason count as {@linkplain #skips() skips};
 * client tests can assert that no draw missed or skipped.
 */
public final class ShaderWarmupRegistry {
    private static final Set<InstanceType<?>> TYPES = new LinkedHashSet<>();
    // Materials may be resource-generation-specific or temporary; retaining them would retain obsolete textures.
    private static final Set<Material> MATERIALS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<ShaderVariant> VARIANTS = new TreeSet<>(ShaderVariant.ORDER);
    private static final Set<Material> PARTICLES_TRANSLUCENT_MATERIALS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<ShaderVariant> MISSED_SET = new TreeSet<>(ShaderVariant.ORDER);
    private static final List<ShaderVariant> MISSED = new ArrayList<>();
    private static long misses;
    private static long skips;

    private ShaderWarmupRegistry() {
    }

    public static synchronized void register(InstanceType<?> type) {
        TYPES.add(type);
    }

    public static synchronized void register(Material material) {
        MATERIALS.add(material);
    }

    /**
     * A variant drawn by materials built after the snapshot, e.g. on first use. Before the resource-apply warm-up
     * (client initialization or a resource reload listener); any thread.
     */
    public static synchronized void register(ShaderVariant variant) {
        VARIANTS.add(variant);
    }

    /**
     * A material a model draws as a translucent particle
     * ({@link dev.engine_room.flywheel.lib.model.PackIdentity#PARTICLES_TRANSLUCENT}); {@code PackTaggedModel}
     * registers its meshes' materials. Shaderpack guests warm that route only for these.
     */
    public static synchronized void registerParticlesTranslucent(Material material) {
        PARTICLES_TRANSLUCENT_MATERIALS.add(material);
    }

    public static synchronized List<InstanceType<?>> types() {
        return List.copyOf(TYPES);
    }

    /**
     * One material per registered variant, in variant order: seeding assigns the cutout/fog indices baked into
     * generated sources, which key cached program binaries. A variant registered without a material is represented
     * by a material with the builder's default state.
     */
    public static synchronized List<Material> materials() {
        Map<ShaderVariant, Material> variants = variants(MATERIALS);
        for (ShaderVariant variant : VARIANTS) variants.putIfAbsent(variant, new VariantMaterial(variant));
        return List.copyOf(variants.values());
    }

    /**
     * Variants of the {@link #registerParticlesTranslucent} materials, ordered as {@link #materials}.
     */
    public static synchronized List<Material> particlesTranslucentMaterials() {
        return List.copyOf(variants(PARTICLES_TRANSLUCENT_MATERIALS).values());
    }

    /**
     * Draw submissions skipped since the last {@link #resetMisses} because their variant or instance type was not
     * registered before the last snapshot; a draw skipped in several passes counts once per pass. Render thread, as
     * every counter below.
     */
    public static long misses() {
        return misses;
    }

    /**
     * Draw submissions of registered variants and instance types skipped since the last {@link #resetMisses}, e.g.
     * while a program covering a later registration builds, or after a backend or option change; counted like
     * {@link #misses}.
     */
    public static long skips() {
        return skips;
    }

    /**
     * The variants of the {@link #misses} draws, in first-miss order.
     */
    public static List<ShaderVariant> missedVariants() {
        return List.copyOf(MISSED);
    }

    /**
     * Clears {@link #misses}, {@link #missedVariants} and {@link #skips}; a variant missing again is logged again.
     */
    public static void resetMisses() {
        misses = 0;
        skips = 0;
        MISSED.clear();
        MISSED_SET.clear();
    }

    /**
     * Counts one miss; true for the variant's first since the last reset.
     */
    @ApiStatus.Internal
    public static boolean recordMiss(ShaderVariant variant) {
        misses++;
        if (!MISSED_SET.add(variant)) return false;
        MISSED.add(variant);
        return true;
    }

    @ApiStatus.Internal
    public static void recordSkip() {
        skips++;
    }

    private static Map<ShaderVariant, Material> variants(Set<Material> materials) {
        Map<ShaderVariant, Material> variants = new TreeMap<>(ShaderVariant.ORDER);
        for (Material material : materials) {
            variants.putIfAbsent(ShaderVariant.of(material), material);
        }
        return variants;
    }

    private record VariantMaterial(ShaderVariant variant) implements Material {
        @Override
        public MaterialShaders shaders() {
            return variant.shaders();
        }

        @Override
        public CutoutShader cutout() {
            return variant.cutout();
        }

        @Override
        public LightShader light() {
            return variant.light();
        }

        @Override
        public FogShader fog() {
            return variant.fog();
        }

        @Override
        public Identifier texture() {
            return TextureAtlas.LOCATION_BLOCKS;
        }

        @Override
        public boolean blur() {
            return false;
        }

        @Override
        public boolean mipmap() {
            return true;
        }

        @Override
        public boolean backfaceCulling() {
            return true;
        }

        @Override
        public boolean polygonOffset() {
            return false;
        }

        @Override
        public DepthTest depthTest() {
            return DepthTest.LEQUAL;
        }

        @Override
        public Transparency transparency() {
            return variant.transparency();
        }

        @Override
        public WriteMask writeMask() {
            return variant.colorWrite() ? WriteMask.COLOR_DEPTH : WriteMask.DEPTH;
        }

        @Override
        public boolean useOverlay() {
            return true;
        }

        @Override
        public boolean useLight() {
            return variant.useLight();
        }

        @Override
        public CardinalLightingMode cardinalLightingMode() {
            return CardinalLightingMode.ENTITY;
        }
    }
}
