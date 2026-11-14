package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.impl.compat.DhStaticSelection;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderBufferHandler.class)
abstract class DhStaticSectionsMixin implements DhStaticSelection {
    @Unique
    private final LongArrayList flywheel$staticSections = new LongArrayList();

    @Inject(method = "buildRenderList", at = @At("HEAD"))
    private void flywheel$beginSelection(RenderParams params, CallbackInfo ci) {
        flywheel$staticSections.clear();
    }

    // This read follows DH's section frustum test and precedes its empty-terrain check.
    @WrapOperation(method = "buildRenderList", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodRenderSection;renderBufferContainer:Lcom/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodBufferContainer;"))
    private LodBufferContainer flywheel$selectStatic(LodRenderSection section,
                                                     Operation<LodBufferContainer> original) {
        if (section.getRenderingEnabled()) flywheel$staticSections.add(section.pos);
        return original.call(section);
    }

    @Override
    public LongList flywheel$staticSections() {
        return flywheel$staticSections;
    }
}
