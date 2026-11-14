package dev.engine_room.flywheel.iris.experimental;

import dev.engine_room.flywheel.iris.IrisRuntime;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ExperimentalMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String _, String _) {
        return IrisRuntime.EXPERIMENTAL;
    }

    @Override
    public void onLoad(String _) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> _, Set<String> _) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String _, ClassNode _, String _, IMixinInfo _) {
    }

    @Override
    public void postApply(String _, ClassNode _, String _, IMixinInfo _) {
    }
}
