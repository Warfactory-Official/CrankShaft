package dev.engine_room.flywheel.lib.model;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * An optional vanilla material or program identity offered to a shader pack. Wrap models in {@link PackTaggedModel}
 * with candidates in preference order; immutable identities are resolved again when the active pack changes.
 */
public sealed interface PackIdentity {
    PackIdentity FLAME = ofIrisEntity("entity_flame");
    PackIdentity SPIDER_EYES = ofIrisProgram("spidereyes");
    PackIdentity ENTITIES_TRANSLUCENT = ofIrisProgram("entities_translucent");
    PackIdentity NAME_TAG = ofIrisProgram("entities_translucent", "name_tag");
    PackIdentity ENTITIES = ofIrisProgram("entities");
    PackIdentity GLOWSTONE = of(Blocks.GLOWSTONE);
    PackIdentity TORCH = of(Blocks.TORCH);
    PackIdentity JACK_O_LANTERN = of(Blocks.LIT_PUMPKIN);
    PackIdentity BEACON = of(Blocks.BEACON);
    PackIdentity LAVA = of(Blocks.LAVA);
    PackIdentity SEA_LANTERN = of(Blocks.SEA_LANTERN);
    PackIdentity FIRE = of(Blocks.FIRE);
    PackIdentity MAGMA = of(Blocks.MAGMA);
    PackIdentity END_PORTAL = of(Blocks.END_PORTAL);
    PackIdentity END_GATEWAY = of(Blocks.END_GATEWAY);
    PackIdentity END_ROD = of(Blocks.END_ROD);
    PackIdentity WATER = of(Blocks.WATER);
    PackIdentity GLASS = of(Blocks.GLASS);
    PackIdentity ICE = of(Blocks.ICE);
    PackIdentity PACKED_ICE = of(Blocks.PACKED_ICE);
    PackIdentity SLIME_BLOCK = of(Blocks.SLIME_BLOCK);
    PackIdentity NETHER_PORTAL = of(Blocks.PORTAL);

    static PackIdentity of(Block block) {
        return of(block.getDefaultState());
    }

    static PackIdentity of(IBlockState state) {
        return new OfBlock(state);
    }

    static PackIdentity ofIrisEntity(String name) {
        return new OfEntity(new ResourceLocation(name));
    }

    static PackIdentity ofIrisProgram(String program) {
        return new OfProgram(program, null);
    }

    static PackIdentity ofIrisProgram(String program, @Nullable String entity) {
        return new OfProgram(program, entity == null ? null : new ResourceLocation(entity));
    }

    static PackIdentity ofItem(ResourceLocation item) {
        return new OfItem(item);
    }

    record OfBlock(IBlockState state) implements PackIdentity {
    }

    record OfEntity(ResourceLocation entity) implements PackIdentity {
    }

    record OfProgram(String program, @Nullable ResourceLocation entity) implements PackIdentity {
    }

    record OfItem(ResourceLocation item) implements PackIdentity {
    }

    final class Effect {
        public static final List<PackIdentity> WARM_LIGHT = List.of(JACK_O_LANTERN, TORCH, GLOWSTONE);
        public static final List<PackIdentity> COOL_LIGHT = List.of(SEA_LANTERN, END_ROD, BEACON, GLOWSTONE);
        public static final List<PackIdentity> FIRE_GLOW = List.of(FLAME, FIRE, TORCH);
        public static final List<PackIdentity> LAVA_GLOW = List.of(LAVA, MAGMA, GLOWSTONE);
        public static final List<PackIdentity> END_GLOW = List.of(END_ROD, END_GATEWAY, END_PORTAL, BEACON);
        public static final List<PackIdentity> GLASS_LIKE = List.of(GLASS);
        public static final List<PackIdentity> WATER_LIKE = List.of(WATER, ICE);
        public static final List<PackIdentity> ICE_LIKE = List.of(ICE, PACKED_ICE, GLASS);
        public static final List<PackIdentity> GEL = List.of(SLIME_BLOCK, GLASS);
        public static final List<PackIdentity> PORTAL = List.of(NETHER_PORTAL, END_PORTAL, END_GATEWAY);

        private Effect() {
        }
    }
}
