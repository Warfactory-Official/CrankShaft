package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.resources.palette.PalettedTextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves vanilla equipment trim overrides on workers, then prepares native palette textures on the render thread.
 * A pending result keeps the whole previous equipment draw set; completed failures propagate to the visual update.
 * Renderer reload replaces the generation, so queued preparation cancels before touching a new texture lifecycle.
 */
final class ArmorTrimTextures {
    private static final RendererReloadCache<Boolean, Generation> GENERATIONS = new RendererReloadCache<>(
            $ -> new Generation());

    private ArmorTrimTextures() {
    }

    @Nullable
    static Prepared getIfReady(ArmorTrim trim, EquipmentClientInfo.LayerType layerType,
                               EquipmentClientInfo equipmentInfo, boolean basePolygonOffset) {
        Identifier texture = trim.pattern().value().assetId();
        Identifier palette = trim.material().value().paletteId();
        for (var override : equipmentInfo.trimOverrides()) {
            if (override.predicate().matches(trim)) {
                texture = override.textureId().orElse(texture);
                palette = override.paletteId().orElse(null);
                break;
            }
        }
        Identifier baseTexture = texture.withPath(path -> layerType.trimAssetPrefix() + "/" + path);
        boolean decal = trim.pattern().value().decal();
        // EQUAL decals must use the same depth bias as the equipment base they overlay.
        Key key = new Key(baseTexture, palette, decal, !decal || basePolygonOffset);
        Generation generation = GENERATIONS.get(true);
        CompletableFuture<Prepared> future = generation.results.computeIfAbsent(key,
                $ -> prepare(generation, key));
        if (GENERATIONS.get(true) != generation || !future.isDone()) {
            return null;
        }
        if (future.isCancelled()) {
            return null;
        }
        Prepared prepared = future.join();
        return GENERATIONS.get(true) == generation ? prepared : null;
    }

    private static CompletableFuture<Prepared> prepare(Generation generation, Key key) {
        CompletableFuture<Prepared> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            if (GENERATIONS.get(true) != generation) {
                future.cancel(false);
                return;
            }
            try {
                PalettedTextureManager.Handle handle = key.palette == null
                        ? new Unpaletted(key.texture.withPath(path -> "textures/" + path + ".png"))
                        : Minecraft.getInstance().getPalettedTextureManager().getOrPrepare(key.texture, key.palette);
                Material material = SimpleMaterial.builder()
                        .texture(handle.textureLocation())
                        .cutout(CutoutShaders.ONE_TENTH)
                        .backfaceCulling(false)
                        .useOverlay(false)
                        .mipmap(false)
                        .polygonOffset(key.polygonOffset)
                        .depthTest(key.decal ? DepthTest.EQUAL : DepthTest.LEQUAL)
                        .writeMask(key.decal ? WriteMask.COLOR : WriteMask.COLOR_DEPTH)
                        .build();
                future.complete(new Prepared(handle, material));
            } catch (RuntimeException | Error error) {
                future.completeExceptionally(error);
                throw error;
            }
        });
        return future;
    }

    record Prepared(PalettedTextureManager.Handle mapping, Material material) {
    }

    private static final class Generation {
        private final ConcurrentHashMap<Key, CompletableFuture<Prepared>> results = new ConcurrentHashMap<>();
    }

    private record Key(Identifier texture, @Nullable Identifier palette, boolean decal, boolean polygonOffset) {
    }

    private record Unpaletted(Identifier textureLocation) implements PalettedTextureManager.Handle {
        @Override
        public float getU(float offset) {
            return offset;
        }

        @Override
        public float getV(float offset) {
            return offset;
        }
    }
}
