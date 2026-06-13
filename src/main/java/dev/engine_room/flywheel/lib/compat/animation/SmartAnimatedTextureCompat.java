package dev.engine_room.flywheel.lib.compat.animation;

import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.optifine.SmartAnimations;
import org.taumc.celeritas.impl.extensions.SpriteExtension;
import zone.rong.loliasm.client.sprite.ondemand.IAnimatedSpriteActivator;
import zone.rong.loliasm.config.LoliConfig;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-frame liveness for animated sprites under SmartAnimations-style renderers
 * (OptiFine / Celeritas / LoliASM).
 * <p>
 * Visualizer workers call {@link #touch(Model)} during their plan; the next
 * {@code RenderTickEvent.START} marks and clears the visible set. Backed by a
 * {@link ConcurrentHashMap}, so a barrier-leaking worker costs at worst one frame of
 * staleness — never a crash or torn state.
 * <p>
 * Downstream wiring: call {@link #register()} once from {@code FMLPreInitializationEvent} to
 * subscribe the tick handler; call {@link #register(Model, TextureAtlasSprite[])} per model when
 * baking, then {@link #touch(Model)} from any thread when the model is rendered this frame.
 */
public final class SmartAnimatedTextureCompat {

    @FunctionalInterface
    public interface Marker {
        Marker NOOP = _ -> {};

        void mark(TextureAtlasSprite sprite);
    }
    private static final MethodHandle GET_ANIMATION_INDEX = bindGetAnimationIndex();
    private static final Marker MARKER = pick();
    public static final boolean ENABLED = MARKER != Marker.NOOP;
    private static final ConcurrentMap<Model, TextureAtlasSprite[]> MODEL_SPRITES = new ConcurrentHashMap<>();
    private static final Set<TextureAtlasSprite> VISIBLE = ConcurrentHashMap.newKeySet();

    private SmartAnimatedTextureCompat() {
    }

    public static void register() {
        if (ENABLED) {
            MinecraftForge.EVENT_BUS.register(new Events());
        }
    }

    public static void register(Model model, TextureAtlasSprite[] sprites) {
        if (sprites.length > 0) {
            MODEL_SPRITES.put(model, sprites);
        }
    }

    public static void touch(Model model) {
        TextureAtlasSprite[] sprites = MODEL_SPRITES.get(model);
        if (sprites == null) return;
        Collections.addAll(VISIBLE, sprites);
    }

    private static Marker pick() {
        Marker[] markers = new Marker[3];
        int n = 0;
        // LoliASM makes TextureAtlasSprite implement IAnimatedSpriteActivator only when its onDemandAnimatedTextures
        // mixin applies, and it auto-disables that mixin under OptiFine or Celeritas (which supply their own
        // smart-animation path). Mirror that gate exactly — a bare isModLoaded check CCEs when the feature is off.
        if (Loader.isModLoaded("loliasm")
                && LoliConfig.instance.onDemandAnimatedTextures
                && !FMLClientHandler.instance().hasOptifine()
                && !Loader.isModLoaded("celeritas")) {
            markers[n++] = sprite -> ((IAnimatedSpriteActivator) sprite).setActive(true);
        }
        if (Loader.isModLoaded("celeritas")) {
            markers[n++] = sprite -> ((SpriteExtension) sprite).celeritas$markActive();
        }
        if (FMLClientHandler.instance().hasOptifine() && GET_ANIMATION_INDEX != null) {
            markers[n++] = sprite -> {
                if (SmartAnimations.isActive()) {
                    try {
                        SmartAnimations.spriteRendered((int) GET_ANIMATION_INDEX.invokeExact(sprite));
                    } catch (Throwable t) {
                        throw new AssertionError(t);
                    }
                }
            };
        }
        if (n == 0) return Marker.NOOP;
        if (n == 1) return markers[0];
        Marker[] composed = Arrays.copyOf(markers, n);
        return sprite -> {
            for (Marker m : composed) {
                m.mark(sprite);
            }
        };
    }

    private static MethodHandle bindGetAnimationIndex() {
        try {
            return MethodHandles.publicLookup().findVirtual(TextureAtlasSprite.class, "getAnimationIndex",
                    MethodType.methodType(int.class));
        } catch (Throwable t) {
            return null;
        }
    }

    public static final class Events {
        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            VISIBLE.forEach(MARKER::mark);
            VISIBLE.clear();
        }

        @SubscribeEvent
        public void onStitch(TextureStitchEvent.Pre event) {
            MODEL_SPRITES.clear();
            VISIBLE.clear();
        }
    }
}
