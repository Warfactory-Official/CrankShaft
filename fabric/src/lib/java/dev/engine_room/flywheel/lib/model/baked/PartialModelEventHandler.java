package dev.engine_room.flywheel.lib.model.baked;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.jetbrains.annotations.ApiStatus;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Unit;

/** Drives {@link PartialModel} population off Fabric's model-loading API. */
@ApiStatus.Internal
public final class PartialModelEventHandler {
    private PartialModelEventHandler() {
    }

    public static void onDefineModels(ModelLoadingPlugin.Context ctx) {
        for (PartialModel partial : PartialModel.all()) {
            ctx.addModel(keyOf(partial), SimpleUnbakedExtraModel.blockStateModel(partial.modelLocation()));
        }
    }

    private static void populate() {
        FabricModelManager manager = (FabricModelManager) Minecraft.getInstance().getModelManager();
        for (PartialModel partial : PartialModel.all()) {
            partial.setBaked(manager.getModel(keyOf(partial)));
        }
    }

    @SuppressWarnings("unchecked")
    private static ExtraModelKey<BlockStateModel> keyOf(PartialModel partial) {
        return (ExtraModelKey<BlockStateModel>) partial.key();
    }

    public static final class ReloadListener implements PreparableReloadListener {
        public static final ReloadListener INSTANCE = new ReloadListener();

        public static final Identifier ID = Identifier.fromNamespaceAndPath(Flywheel.ID, "partial_models");

        private ReloadListener() {
        }

        @Override
        public CompletableFuture<Void> reload(SharedState sharedState, Executor backgroundExecutor, PreparationBarrier preparationBarrier, Executor gameExecutor) {
            // No async prepare -- partials only fetch already-baked models on the game thread after MODELS applies.
            return preparationBarrier.wait(Unit.INSTANCE)
                    .thenRunAsync(PartialModelEventHandler::populate, gameExecutor);
        }
    }
}
