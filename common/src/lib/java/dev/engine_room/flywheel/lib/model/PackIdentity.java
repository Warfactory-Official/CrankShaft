package dev.engine_room.flywheel.lib.model;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

public sealed interface PackIdentity {
    record OfBlock(Block block) implements PackIdentity {
    }

    record OfState(BlockState state) implements PackIdentity {
    }

    record OfEntity(String irisName) implements PackIdentity {
    }

    /**
     * Draw through the pack program Iris routes this vanilla draw to ({@code spidereyes},
     * {@code entities_translucent}, {@code entities}), keeping the draw's own ids.
     */
    record OfProgram(String irisProgram) implements PackIdentity {
    }

    /**
     * Report {@code irisItem} as the rendered item ({@code currentRenderedItemId}), alongside the other identities.
     */
    record OfItem(Identifier irisItem) implements PackIdentity {
    }

    static PackIdentity of(Block block) {
        return new OfBlock(block);
    }

    static PackIdentity of(BlockState state) {
        return new OfState(state);
    }

    static PackIdentity ofIrisEntity(String irisName) {
        return new OfEntity(irisName);
    }

    static PackIdentity ofIrisProgram(String irisProgram) {
        return new OfProgram(irisProgram);
    }

    static PackIdentity ofIrisItem(Identifier irisItem) {
        return new OfItem(irisItem);
    }

    PackIdentity FLAME = ofIrisEntity("entity_flame");
    PackIdentity SPIDER_EYES = ofIrisProgram("spidereyes");
    PackIdentity ENTITIES_TRANSLUCENT = ofIrisProgram("entities_translucent");
    PackIdentity ENTITIES = ofIrisProgram("entities");
    PackIdentity GLOWSTONE = of(Blocks.GLOWSTONE);
    PackIdentity TORCH = of(Blocks.TORCH);
    PackIdentity JACK_O_LANTERN = of(Blocks.JACK_O_LANTERN);
    PackIdentity BEACON = of(Blocks.BEACON);
    PackIdentity LAVA = of(Blocks.LAVA);
    PackIdentity SEA_LANTERN = of(Blocks.SEA_LANTERN);
    PackIdentity SOUL_TORCH = of(Blocks.SOUL_TORCH);
    PackIdentity SHROOMLIGHT = of(Blocks.SHROOMLIGHT);
    PackIdentity FIRE = of(Blocks.FIRE);
    PackIdentity SOUL_FIRE = of(Blocks.SOUL_FIRE);
    PackIdentity TORCHFLOWER = of(Blocks.TORCHFLOWER);
    PackIdentity MAGMA = of(Blocks.MAGMA_BLOCK);
    PackIdentity OCHRE_FROGLIGHT = of(Blocks.OCHRE_FROGLIGHT);
    PackIdentity VERDANT_FROGLIGHT = of(Blocks.VERDANT_FROGLIGHT);
    PackIdentity PEARLESCENT_FROGLIGHT = of(Blocks.PEARLESCENT_FROGLIGHT);
    PackIdentity END_PORTAL = of(Blocks.END_PORTAL);
    PackIdentity END_GATEWAY = of(Blocks.END_GATEWAY);
    PackIdentity SOUL_LANTERN = of(Blocks.SOUL_LANTERN);
    PackIdentity GLOW_LICHEN = of(Blocks.GLOW_LICHEN);
    PackIdentity CRYING_OBSIDIAN = of(Blocks.CRYING_OBSIDIAN);
    PackIdentity AMETHYST_CLUSTER = of(Blocks.AMETHYST_CLUSTER);
    PackIdentity END_ROD = of(Blocks.END_ROD);
    PackIdentity BREWING_STAND = of(Blocks.BREWING_STAND);
    PackIdentity ENCHANTING_TABLE = of(Blocks.ENCHANTING_TABLE);
    PackIdentity LANTERN = of(Blocks.LANTERN);
    PackIdentity SCULK_CATALYST = of(Blocks.SCULK_CATALYST);
    PackIdentity WATER = of(Blocks.WATER);
    PackIdentity ICE = of(Blocks.ICE);
    PackIdentity WHEAT = of(Blocks.WHEAT);
    PackIdentity BLACK_STAINED_GLASS = of(Blocks.STAINED_GLASS.pick(DyeColor.BLACK));
    PackIdentity GRAY_STAINED_GLASS = of(Blocks.STAINED_GLASS.pick(DyeColor.GRAY));
    PackIdentity WHITE_STAINED_GLASS = of(Blocks.STAINED_GLASS.pick(DyeColor.WHITE));
    PackIdentity SLIME_BLOCK = of(Blocks.SLIME_BLOCK);
    PackIdentity SHORT_GRASS = of(Blocks.SHORT_GRASS);
    PackIdentity GLASS = of(Blocks.GLASS);
    PackIdentity TINTED_GLASS = of(Blocks.TINTED_GLASS);
    PackIdentity HONEY_BLOCK = of(Blocks.HONEY_BLOCK);
    PackIdentity PACKED_ICE = of(Blocks.PACKED_ICE);
    PackIdentity BLUE_ICE = of(Blocks.BLUE_ICE);
    PackIdentity VINE = of(Blocks.VINE);
    PackIdentity COBWEB = of(Blocks.COBWEB);
    PackIdentity GLASS_PANE = of(Blocks.GLASS_PANE);
    PackIdentity NETHER_PORTAL = of(Blocks.NETHER_PORTAL);
    PackIdentity OAK_LEAVES = of(Blocks.OAK_LEAVES);
    PackIdentity LIT_CAMPFIRE = of(Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
    PackIdentity LIT_SOUL_CAMPFIRE = of(Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
    PackIdentity LIT_REDSTONE_LAMP = of(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
    PackIdentity LIT_CANDLE = of(Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
    PackIdentity GLOW_BERRY_VINES = of(Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true));

    final class Effect {
        public static final List<PackIdentity> WARM_LIGHT = List.of(LANTERN, JACK_O_LANTERN, TORCH, GLOWSTONE);
        public static final List<PackIdentity> COOL_LIGHT = List.of(SEA_LANTERN, END_ROD, BEACON, GLOWSTONE);
        public static final List<PackIdentity> SOUL_LIGHT = List.of(SOUL_TORCH, SOUL_LANTERN, SOUL_FIRE, TORCH);
        public static final List<PackIdentity> FIRE_GLOW = List.of(FLAME, LIT_CAMPFIRE, PackIdentity.FIRE, TORCH);
        public static final List<PackIdentity> LAVA_GLOW = List.of(LAVA, MAGMA, GLOWSTONE);
        public static final List<PackIdentity> ORGANIC_GLOW = List.of(SHROOMLIGHT, GLOW_LICHEN, OCHRE_FROGLIGHT, GLOWSTONE);
        public static final List<PackIdentity> ARCANE = List.of(ENCHANTING_TABLE, AMETHYST_CLUSTER, CRYING_OBSIDIAN, BEACON);
        public static final List<PackIdentity> END_GLOW = List.of(END_ROD, END_GATEWAY, END_PORTAL, BEACON);
        public static final List<PackIdentity> REDSTONE_GLOW = List.of(LIT_REDSTONE_LAMP, TORCH, GLOWSTONE);
        public static final List<PackIdentity> GLASS_LIKE = List.of(GLASS, WHITE_STAINED_GLASS, GRAY_STAINED_GLASS);
        public static final List<PackIdentity> DARK_GLASS = List.of(TINTED_GLASS, BLACK_STAINED_GLASS, GLASS);
        public static final List<PackIdentity> WATER_LIKE = List.of(WATER, ICE);
        public static final List<PackIdentity> ICE_LIKE = List.of(ICE, PACKED_ICE, BLUE_ICE, GLASS);
        public static final List<PackIdentity> GEL = List.of(SLIME_BLOCK, HONEY_BLOCK, GLASS);
        public static final List<PackIdentity> FOLIAGE = List.of(SHORT_GRASS, VINE, OAK_LEAVES, WHEAT);
        public static final List<PackIdentity> PORTAL = List.of(NETHER_PORTAL, END_PORTAL, END_GATEWAY);
        public static final List<PackIdentity> GOSSAMER = List.of(COBWEB, WHITE_STAINED_GLASS, GLASS);

        private Effect() {
        }
    }
}
