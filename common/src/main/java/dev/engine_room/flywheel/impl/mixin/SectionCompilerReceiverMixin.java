package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerReceiverMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results flywheel$receiverScope(SectionPos section, RenderSectionRegion region,
                                                           VertexSorting sorting, SectionBufferBuilderPack builders,
                                                           Operation<SectionCompiler.Results> original) {
        try (var _ = TerrainReceiverCompilation.begin(
                ((RenderSectionRegionReceiverAccessor) region).flywheel$receiverLevel(), section.asLong())) {
            return original.call(section, region, sorting, builders);
        }
    }
}
