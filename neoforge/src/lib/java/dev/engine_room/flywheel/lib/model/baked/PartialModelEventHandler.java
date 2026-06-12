package dev.engine_room.flywheel.lib.model.baked;

import org.jetbrains.annotations.ApiStatus;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

/** Drives {@link PartialModel} population off NeoForge's standalone-model events. */
@ApiStatus.Internal
public final class PartialModelEventHandler {
    private PartialModelEventHandler() {
    }

    public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
        for (PartialModel partial : PartialModel.all()) {
            event.register(keyOf(partial), SimpleUnbakedStandaloneModel.blockStateModel(partial.modelLocation()));
        }
    }

    public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        ModelManager manager = event.getModelManager();
        for (PartialModel partial : PartialModel.all()) {
            partial.setBaked(manager.getStandaloneModel(keyOf(partial)));
        }
    }

    @SuppressWarnings("unchecked")
    private static StandaloneModelKey<BlockStateModel> keyOf(PartialModel partial) {
        return (StandaloneModelKey<BlockStateModel>) partial.key();
    }
}
