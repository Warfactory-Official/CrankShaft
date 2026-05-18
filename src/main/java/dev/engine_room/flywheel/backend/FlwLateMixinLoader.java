package dev.engine_room.flywheel.backend;

import zone.rong.mixinbooter.Context;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Arrays;
import java.util.List;

@SuppressWarnings("deprecation") // Cleanroom shaded ILateMixinLoader
public class FlwLateMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList(
                "flywheel.sodium_fork.mixin.json",
                "flywheel.cdl_sodium_fork.mixin.json",
                "flywheel.celeritas.mixin.json",
                "flywheel.renderlib.mixin.json"
        );
    }

    @Override
    public boolean shouldMixinConfigQueue(Context context) {
        return switch (context.mixinConfig()) {
            case "flywheel.sodium_fork.mixin.json" -> isSodiumForkPresent(context);
            case "flywheel.cdl_sodium_fork.mixin.json" -> context.isModPresent("celeritasdynamiclights") && isSodiumForkPresent(context);
            case "flywheel.celeritas.mixin.json" -> context.isModPresent("celeritas");
            case "flywheel.renderlib.mixin.json" -> context.isModPresent("renderlib");
            default -> true;
        };
    }

    private static boolean isSodiumForkPresent(Context context) {
        return context.isModPresent("neonium") || context.isModPresent("vintagium") || context.isModPresent("relictium");
    }
}
