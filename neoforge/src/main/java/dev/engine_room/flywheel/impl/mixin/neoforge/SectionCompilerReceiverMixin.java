package dev.engine_room.flywheel.impl.mixin.neoforge;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import dev.engine_room.flywheel.impl.mixin.RenderSectionRegionReceiverAccessor;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerReceiverMixin {
    @WrapMethod(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;")
    private SectionCompiler.Results flywheel$receiverScope(SectionPos section, RenderSectionRegion region,
                                                           VertexSorting sorting, SectionBufferBuilderPack builders,
                                                           List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
                                                           Operation<SectionCompiler.Results> original) {
        try (var _ = TerrainReceiverCompilation.begin(
                ((RenderSectionRegionReceiverAccessor) region).flywheel$receiverLevel(), section.asLong())) {
            return original.call(section, region, sorting, builders, additionalRenderers);
        }
    }
}
