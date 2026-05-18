package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class FlwEvents {
    public static final FlwEvents INSTANCE = new FlwEvents();

    private FlwEvents() {
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        VisualizationEventHandler.onClientTick(mc, mc.world);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        VisualizationEventHandler.onWorldUnload(event.getWorld());
        LevelAttached.invalidateLevel(event.getWorld());
    }

    @SubscribeEvent
    public void onRenderGameOverlayText(RenderGameOverlayEvent.Text event) {
        FlwDebugInfo.addDebugInfo(Minecraft.getMinecraft(), event.getLeft());
    }

    @SubscribeEvent
    public void onModelBake(ModelBakeEvent event) {
        PartialModel.onModelBake(event);
    }

    @SubscribeEvent
    public void onTextureStitchPre(TextureStitchEvent.Pre event) {
        PartialModel.onTextureStitchPre(event);
    }

    @SubscribeEvent
    public void onClientChat(ClientChatEvent event) {
        if (CopyClickAction.tryHandle(event.getOriginalMessage())) {
            event.setCanceled(true);
        }
    }
}
