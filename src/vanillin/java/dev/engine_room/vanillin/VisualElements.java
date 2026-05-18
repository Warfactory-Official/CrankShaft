package dev.engine_room.vanillin;

import dev.engine_room.flywheel.lib.compose.VisualElement;
import dev.engine_room.flywheel.lib.visual.ItemVisual;
import dev.engine_room.vanillin.elements.FireElement;
import dev.engine_room.vanillin.elements.HitboxElement;
import dev.engine_room.vanillin.elements.ShadowElement;
import dev.engine_room.vanillin.visuals.ItemFrameVisual;
import dev.engine_room.vanillin.visuals.MinecartVisual;
import dev.engine_room.vanillin.visuals.TntMinecartVisual;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartTNT;

public final class VisualElements {
    public static final VisualElement<Entity, Boolean> HITBOX = HitboxElement::new;
    public static final VisualElement<Entity, ShadowElement.Config> SHADOW = ShadowElement::new;
    public static final VisualElement.Unit<Entity> FIRE = FireElement::new;

    public static final VisualElement.Unit<EntityMinecart> MINECART = MinecartVisual::new;
    public static final VisualElement.Unit<EntityMinecartTNT> TNT_MINECART = TntMinecartVisual::new;
    public static final VisualElement.Unit<EntityItem> ITEM_ENTITY = ItemVisual::new;
    public static final VisualElement.Unit<EntityItemFrame> ITEM_FRAME = ItemFrameVisual::new;

    private VisualElements() {
    }
}
