package dev.engine_room.flywheel.lib.compat;

import dev.engine_room.flywheel.impl.FlwImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class BiomeBlendCompat {
    public static final IntSupplier RADIUS = pick();

    private BiomeBlendCompat() {
    }

    private static IntSupplier pick() {
        IntSupplier s;
        if (Loader.isModLoaded("celeritas")) {
            return mustBind("org.taumc.celeritas.CeleritasVintage", "quality", "legacyBiomeBlendRadius");
        }
        // Neonium also registers the `vintagium` modid so it must be checked first
        if (Loader.isModLoaded("neonium")) {
            return mustBind("io.neox.neonium.Neonium", "quality", "biomeBlendRadius");
        }
        // Vintagium and Relictium both register as modid `vintagium`; probe each FQN.
        if (Loader.isModLoaded("vintagium")) {
            if ((s = tryBind("me.jellysquid.mods.sodium.client.SodiumClientMod", "quality", "biomeBlendRadius")) != null) return s;
            if ((s = tryBind("io.themade4.relictium.Relictium", "quality", "biomeBlendRadius")) != null) return s;
            throw new IllegalStateException("BiomeBlendCompat: vintagium modid loaded but no known fork class is on the classpath");
        }
        if (FMLClientHandler.instance().hasOptifine()) return bindOptifineSmoothBiomes();
        return () -> 0;
    }

    private static IntSupplier mustBind(String accessorOwnerFqn, String groupField, String valueField) {
        IntSupplier s = tryBind(accessorOwnerFqn, groupField, valueField);
        if (s == null) {
            throw new IllegalStateException("BiomeBlendCompat: " + accessorOwnerFqn + " not on classpath despite its mod being loaded");
        }
        return s;
    }

    private static @Nullable IntSupplier tryBind(String accessorOwnerFqn, String groupField, String valueField) {
        Class<?> owner;
        try {
            owner = Class.forName(accessorOwnerFqn);
        } catch (ClassNotFoundException e) {
            return null;
        }
        try {
            Method accessor = owner.getMethod("options");
            Object opts = accessor.invoke(null);
            Object group = opts.getClass().getField(groupField).get(opts);
            Field field = group.getClass().getField(valueField);
            IntSupplier supplier = asIntSupplier(group, field);
            FlwImpl.LOGGER.info("BiomeBlendCompat bound to {}", accessorOwnerFqn);
            return supplier;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BiomeBlendCompat: " + accessorOwnerFqn + " is loaded but binding failed", e);
        }
    }

    private static IntSupplier bindOptifineSmoothBiomes() {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        try {
            Field field = settings.getClass().getField("ofSmoothBiomes");
            BooleanSupplier bs = asBooleanSupplier(settings, field);
            FlwImpl.LOGGER.info("BiomeBlendCompat bound to OptiFine ofSmoothBiomes");
            return () -> bs.getAsBoolean() ? 2 : 0;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BiomeBlendCompat: OptiFine present but binding failed", e);
        }
    }

    private static IntSupplier asIntSupplier(Object captured, Field field) throws IllegalAccessException {
        MethodHandle getter = MethodHandles.lookup().unreflectGetter(field).bindTo(captured);
        return MethodHandleProxies.asInterfaceInstance(IntSupplier.class, getter);
    }

    private static BooleanSupplier asBooleanSupplier(Object captured, Field field) throws IllegalAccessException {
        MethodHandle getter = MethodHandles.lookup().unreflectGetter(field).bindTo(captured);
        return MethodHandleProxies.asInterfaceInstance(BooleanSupplier.class, getter);
    }
}
