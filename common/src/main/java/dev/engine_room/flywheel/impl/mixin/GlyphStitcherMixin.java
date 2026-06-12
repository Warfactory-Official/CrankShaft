package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.lib.visual.component.GlyphAtlases;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records each newly-stitched font atlas's {@code GpuTextureView -> Identifier} mapping into {@link GlyphAtlases}.
 */
@Mixin(GlyphStitcher.class)
abstract class GlyphStitcherMixin {
    @Inject(method = "stitch", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V"))
    private void flywheel$recordAtlas(CallbackInfoReturnable<BakedSheetGlyph> cir,
                                      @Local Identifier name, @Local FontTexture texture) {
        GlyphAtlases.register(texture.getTextureView(), name);
    }
}
