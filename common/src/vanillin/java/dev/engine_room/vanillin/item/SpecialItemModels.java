package dev.engine_room.vanillin.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves an item stack's special-model layers (in-hand trident/shield/skull/banner) into instanceable draw
 * plans, mirroring {@code ItemStackRenderState}'s special branch; unsupported renderers resolve to nothing.
 */
public final class SpecialItemModels {
    private SpecialItemModels() {
    }

    public static List<Resolved> resolve(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner,
                                         int seed) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStackRenderState renderState = new ItemStackRenderState();
        minecraft.getItemModelResolver()
                 .updateForTopItem(renderState, stack, displayContext, minecraft.level, owner, seed);
        List<Resolved> out = null;
        PoseStack.Pose pose = new PoseStack.Pose();
        for (int i = 0; i < renderState.activeLayerCount; i++) {
            ItemStackRenderState.LayerRenderState layer = renderState.layers[i];
            if (layer.specialRenderer == null) {
                continue;
            }
            Key key = keyFor(layer.specialRenderer, stack, layer.foilType != ItemStackRenderState.FoilType.NONE);
            if (key == null) {
                continue;
            }
            pose.setIdentity();
            layer.applyTransform(pose);
            if (out == null) {
                out = new ArrayList<>(2);
            }
            out.add(new Resolved(key, new Matrix4f(pose.pose())));
        }
        return out == null ? List.of() : out;
    }

    @Nullable
    private static Key keyFor(SpecialModelRenderer<?> renderer, ItemStack stack, boolean foil) {
        if (renderer instanceof TridentSpecialRenderer) {
            return new TridentKey(foil);
        }
        if (renderer instanceof ShieldSpecialRenderer shield) {
            DataComponentMap components = shield.extractArgument(stack);
            BannerPatternLayers patterns = components != null
                    ? components.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY)
                    : BannerPatternLayers.EMPTY;
            DyeColor baseColor = components != null ? components.get(DataComponents.BASE_COLOR) : null;
            return new ShieldKey(baseColor, patterns, foil);
        }
        if (renderer instanceof SkullSpecialRenderer || renderer instanceof PlayerHeadSpecialRenderer) {
            if (stack.getItem() instanceof BlockItem block && block.getBlock() instanceof AbstractSkullBlock skull) {
                return skullKey(skull.getType(), stack.get(DataComponents.PROFILE));
            }
            return null;
        }
        if (renderer instanceof BannerSpecialRenderer && stack.getItem() instanceof BannerItem banner) {
            return new BannerKey(banner.getColor(),
                    stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY));
        }
        return null;
    }

    @Nullable
    public static SkullKey skullKey(SkullBlock.Type type, @Nullable ResolvableProfile profile) {
        if (type == SkullBlock.Types.PLAYER && profile != null) {
            PlayerSkinRenderCache.RenderInfo info = Minecraft.getInstance().playerSkinRenderCache()
                                                             .getOrDefault(profile);
            return new SkullKey(type, info.playerSkin().body().texturePath(), true);
        }
        Identifier texture = SkullBlockRenderer.SKIN_BY_TYPE.get(type);
        return texture == null ? null : new SkullKey(type, texture, false);
    }

    public sealed interface Key permits SkullKey, TridentKey, ShieldKey, BannerKey {
    }

    public record SkullKey(SkullBlock.Type type, Identifier texture, boolean translucentSkin) implements Key {
    }

    public record TridentKey(boolean foil) implements Key {
    }

    public record ShieldKey(@Nullable DyeColor baseColor, BannerPatternLayers patterns, boolean foil) implements Key {
    }

    public record BannerKey(DyeColor baseColor, BannerPatternLayers patterns) implements Key {
    }

    public record Resolved(Key key, Matrix4f transform) {
    }
}
