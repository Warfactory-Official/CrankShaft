package dev.engine_room.flywheel.lib.model.baked;

import com.google.common.collect.MapMaker;
import dev.engine_room.flywheel.impl.FlwImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.model.TRSRTransformation;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentMap;

/**
 * A helper class for loading and accessing JSON models not directly used by any blocks or items.
 * <br>
 * Creating a PartialModel will make Minecraft automatically load the associated modelLocation.
 * <br>
 * Once Minecraft has finished baking all models, all PartialModels will have their bakedModel fields populated.
 * <br>
 * {@link #ALL} holds weak values; consumers must keep a strong reference (typically {@code static final}).
 */
public final class PartialModel {
    static final ConcurrentMap<ResourceLocation, PartialModel> ALL = new MapMaker().weakValues().makeMap();
    static volatile boolean populateOnInit = false;

    private final ResourceLocation modelLocation;
    @Nullable
    IBakedModel bakedModel;

    private PartialModel(ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;

        if (populateOnInit) {
            bakedModel = bake(modelLocation);
        }
    }

    public static PartialModel of(ResourceLocation modelLocation) {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("PartialModel.of must be called from the Minecraft client thread; got " + Thread.currentThread().getName());
        }
        return ALL.computeIfAbsent(modelLocation, PartialModel::new);
    }

    @Nullable
    public IBakedModel get() {
        return bakedModel;
    }

    public ResourceLocation modelLocation() {
        return modelLocation;
    }

    public static void onTextureStitchPre(TextureStitchEvent.Pre event) {
        TextureMap atlas = event.getMap();
        if (atlas != Minecraft.getMinecraft().getTextureMapBlocks()) {
            return;
        }
        for (PartialModel pm : ALL.values()) {
            try {
                IModel model = ModelLoaderRegistry.getModel(pm.modelLocation);
                for (ResourceLocation tex : model.getTextures()) {
                    atlas.registerSprite(tex);
                }
            } catch (Exception e) {
                FlwImpl.LOGGER.warn("Failed to register textures for PartialModel '{}'", pm.modelLocation, e);
            }
        }
    }

    public static void onModelBake(ModelBakeEvent event) {
        populateOnInit = true;
        for (PartialModel pm : ALL.values()) {
            pm.bakedModel = bake(pm.modelLocation);
        }
    }

    @Nullable
    private static IBakedModel bake(ResourceLocation loc) {
        try {
            IModel model = ModelLoaderRegistry.getModel(loc);
            return model.bake(TRSRTransformation.identity(), DefaultVertexFormats.ITEM,
                    location -> Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(location.toString()));
        } catch (Exception e) {
            FlwImpl.LOGGER.warn("Failed to bake PartialModel '{}'", loc, e);
            return null;
        }
    }
}
