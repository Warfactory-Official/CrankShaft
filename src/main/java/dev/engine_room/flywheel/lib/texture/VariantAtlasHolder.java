package dev.engine_room.flywheel.lib.texture;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.resource.ISelectiveResourceReloadListener;
import net.minecraftforge.client.resource.VanillaResourceType;

import java.util.function.Consumer;

/**
 * A reusable {@link VariantAtlas} holder for a fixed set of variant cells: encapsulates the atlas + its
 * cutout entity {@link Material} and the TEXTURES resource-reload listener that (re)builds them on the render
 * thread. {@link #register()} fires the listener synchronously to bootstrap, so it MUST run at {@code init},
 * NOT preInit: the build uploads through {@code Minecraft.getTextureManager()}, which Forge leaves null until
 * just after {@code beginMinecraftLoading} (preInit). Call {@link #register()} once from a visual's
 * registration; per instance call {@link #cell} for the
 * sub-rect (fed to {@code UvTransformedInstance.uvRegion}) and {@link #material} as the body texture. All
 * variants sharing this holder batch into one instancer per bone. Reads are concurrent-safe (volatile publish);
 * builds happen only on the reload thread. The {@code populator} ctor supports composited cells (e.g. horse
 * coat × markings via {@code addLayered}); the varargs ctor is the single-source-per-cell shortcut.
 */
public final class VariantAtlasHolder {
    private final ResourceLocation location;
    private final Consumer<VariantAtlasBuilder> populator;

    private volatile VariantAtlas atlas;
    private volatile Material material;

    public VariantAtlasHolder(ResourceLocation location, ResourceLocation... sources) {
        this(location, builder -> {
            for (ResourceLocation source : sources) {
                builder.add(source);
            }
        });
    }

    public VariantAtlasHolder(ResourceLocation location, Consumer<VariantAtlasBuilder> populator) {
        this.location = location;
        this.populator = populator;
    }

    public void register() {
        IReloadableResourceManager mgr = (IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager();
        mgr.registerReloadListener((ISelectiveResourceReloadListener) (m, predicate) -> {
            if (!predicate.test(VanillaResourceType.TEXTURES)) {
                return;
            }
            VariantAtlasBuilder builder = new VariantAtlasBuilder(location);
            populator.accept(builder);
            VariantAtlas old = atlas;
            atlas = builder.build();
            material = SimpleMaterial.builderOf(Materials.CUTOUT_NO_CULL)
                    .cardinalLightingMode(CardinalLightingMode.ENTITY)
                    .texture(location)
                    .mipmap(false)
                    .blur(false)
                    .build();
            if (old != null) {
                old.delete();
            }
        });
    }

    public VariantAtlas atlas() {
        return atlas;
    }

    public Material material() {
        return material;
    }

    /** Cell by build/add order — the per-frame path. NPEs before the first build; gate visuals on
     *  {@link #ready()} or {@link #contains}. */
    public VariantAtlas.Cell cell(int index) {
        return atlas.cell(index);
    }

    public boolean ready() {
        return atlas != null;
    }

    public boolean contains(ResourceLocation source) {
        VariantAtlas a = atlas;
        return a != null && a.contains(source);
    }
}
