package dev.engine_room.flywheel.lib.model.baked;

import org.jspecify.annotations.Nullable;

import dev.engine_room.flywheel.lib.model.baked.PartialModelController;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;

public class PartialModelControllerImpl implements PartialModelController {
    @Override
    public Object createKey(Identifier modelLocation) {
        // The debug name doubles as the model id for error messages; identity, not the name, distinguishes keys.
        return ExtraModelKey.<BlockStateModel>create(modelLocation::toString);
    }

    @Override
    @Nullable
    public BlockStateModel getBaked(Object key) {
        @SuppressWarnings("unchecked")
        ExtraModelKey<BlockStateModel> extraKey = (ExtraModelKey<BlockStateModel>) key;
        return ((FabricModelManager) Minecraft.getInstance().getModelManager()).getModel(extraKey);
    }
}
