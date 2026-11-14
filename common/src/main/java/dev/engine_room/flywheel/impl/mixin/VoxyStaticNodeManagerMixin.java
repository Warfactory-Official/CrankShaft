package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.far.VoxyStaticSections;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NodeManager.class)
abstract class VoxyStaticNodeManagerMixin implements VoxyStaticVisuals.NodeAccess {
    @Shadow
    @Final
    private NodeStore nodeData;
    @Shadow
    @Final
    private Long2IntOpenHashMap activeSectionMap;
    @Unique
    private VoxyStaticSections flywheel$staticSections;

    @Override
    public void flywheel$staticSections(VoxyStaticSections sections) {
        flywheel$staticSections = sections;
    }

    @ModifyVariable(method = "processGeometryResult", at = @At("HEAD"), argsOnly = true)
    private BuiltSection flywheel$includeStaticGeometry(BuiltSection section) {
        return flywheel$staticSections.include(section);
    }

    @WrapMethod(method = "processChildChange")
    private void flywheel$includeStaticChildren(long position, byte children, Operation<Void> original) {
        original.call(position, (byte) (children | flywheel$staticSections.children(position)));
        flywheel$recordNativePosition(position);
    }

    @Inject(method = "processGeometryResult", at = @At("RETURN"))
    private void flywheel$nativeGeometryCompleted(BuiltSection source, CallbackInfo _) {
        flywheel$recordNativePosition(source.position);
    }

    @Unique
    private void flywheel$recordNativePosition(long position) {
        int node = activeSectionMap.get(position);
        if (node != -1 && (node & 0xc0000000) != 0x80000000) flywheel$recordNativeNode(node & 0xffffff);
    }

    @Inject(method = "writeNode", at = @At("TAIL"))
    private void flywheel$nativeNodeWritten(int node, long address, CallbackInfo _) {
        if (nodeData.nodeExists(node)) flywheel$recordNativeNode(node);
    }

    @Inject(method = "_recurseRemoveNode", at = @At("RETURN"))
    private void flywheel$nativeNodeRemoved(long position, boolean onlyRemoveChildren, CallbackInfo _) {
        if (!onlyRemoveChildren) flywheel$staticSections.nativeNodeRemoved(position);
    }

    @Unique
    private void flywheel$recordNativeNode(int node) {
        flywheel$staticSections.nativeNode(nodeData.nodePosition(node), nodeData.getNodeGeometry(node),
                nodeData.getNodeChildExistence(node), nodeData.isNodeRequestInFlight(node)
                        || nodeData.isNodeGeometryInFlight(node));
    }
}

#endif
