package dev.engine_room.flywheel.backend.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MixinCompatibilityPlugin implements IMixinConfigPlugin {
    private static final String HELPER = "dev/engine_room/flywheel/lib/visualization/VisualizationHelper";

    private static void redirect(MethodInsnNode call, String name, String descriptor) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = HELPER;
        call.name = name;
        call.desc = descriptor;
        call.itf = false;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!mixinClassName.equals("dev.engine_room.flywheel.backend.mixin.MixinRenderGlobal")
                && !mixinClassName.equals("dev.engine_room.flywheel.backend.mixin.MixinEntityRenderer")) return;

        // CleanMix no longer exposes mixin-source transformers. PostApply runs after every mixin's injection pass,
        // so these rewrites survive overwrites without relying on classloader exclusions or changing ClassInfo.
        var rewrittenPassGates = new HashSet<String>();
        for (var method : targetClass.methods) {
            var merged = Annotations.getVisible(method, MixinMerged.class);
            if (merged == null) continue;
            String source = Annotations.getValue(merged, "mixin");
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                switch (source) {
                    case "me.jellysquid.mods.sodium.mixin.features.chunk_rendering.MixinRenderGlobal",
                         "me.jellysquid.mods.sodium.mixin.features.chunk_rendering.MixinWorldRenderer",
                         "org.embeddedt.vintagefix.mixin.bugfix.entity_disappearing.MixinRenderGlobal" -> {
                        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                                && call.owner.equals("net/minecraft/entity/Entity")
                                && call.name.equals("shouldRenderInPass") && call.desc.equals("(I)Z")
                                && rewrittenPassGates.add(source)) {
                            redirect(call, "shouldRenderInPass", "(Lnet/minecraft/entity/Entity;I)Z");
                        }
                    }
                    case "org.taumc.celeritas.mixin.core.terrain.RenderGlobalMixin" -> {
                        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                                && call.owner.equals("net/minecraft/client/renderer/entity/RenderManager")
                                && (call.name.equals("shouldRender") || call.name.equals("func_178635_a"))
                                && call.desc.equals(
                                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z")) {
                            redirect(call, "shouldRender",
                                    "(Lnet/minecraft/client/renderer/entity/RenderManager;Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z");
                        }
                    }
                    case "meldexun.renderlib.mixin.caching.boundingbox.MixinEntityRenderer" -> {
                        if (call.getOpcode() == Opcodes.INVOKEINTERFACE
                                && call.owner.equals("meldexun/renderlib/api/IBoundingBoxCache")
                                && call.name.equals("updateCachedBoundingBox") && call.desc.equals("(D)V")) {
                            String helper = method.desc.contains("Lnet/minecraft/tileentity/TileEntity;")
                                    ? "updateCachedBoundingBoxTile" : "updateCachedBoundingBoxEntity";
                            redirect(call, helper, "(Lmeldexun/renderlib/api/IBoundingBoxCache;D)V");
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
