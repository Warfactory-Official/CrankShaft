package dev.engine_room.flywheel.lib.model.baked;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.jspecify.annotations.Nullable;

public class PartialModelControllerImpl implements PartialModelController {
    @Override
    public Object createKey(Identifier modelLocation) {
        // The debug name doubles as the model id for logging; identity, not the name, distinguishes keys.
        return new StandaloneModelKey<BlockStateModel>(modelLocation::toString);
    }

    @Override
    @Nullable
    public BlockStateModel getBaked(Object key) {
        @SuppressWarnings("unchecked")
        StandaloneModelKey<BlockStateModel> standaloneKey = (StandaloneModelKey<BlockStateModel>) key;
        ModelManager modelManager = Minecraft.getInstance().getModelManager();
        return modelManager.getStandaloneModel(standaloneKey);
    }
}
