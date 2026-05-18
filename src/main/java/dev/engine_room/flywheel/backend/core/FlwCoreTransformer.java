package dev.engine_room.flywheel.backend.core;

import net.minecraft.launchwrapper.IClassTransformer;

public final class FlwCoreTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null) return basicClass;

        // a class is transformed at most once in this switch
        byte[] result = switch (transformedName) {
            case RenderGlobalTransformer.TARGET ->
                    RenderGlobalTransformer.transform(name, transformedName, basicClass);
            default -> // Entities, TileEntities, and their subclasses
                    VisualizerTransformer.transform(transformedName, basicClass);
        };
        return ArbCallSiteTransformer.transform(name, transformedName, result);
    }
}
