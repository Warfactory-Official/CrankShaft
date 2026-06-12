package dev.engine_room.flywheel.impl;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.stream.Stream;

/**
 * Shared implementation of {@code /flywheel stress} commands; the Brigadier tree lives per-loader.
 */
public final class FlwStress {
    public static final int STRESS_MAX = 100_000;
    public static final String[] STRESS_COUNTS = {"100", "1000", "10000"};
    private static final IntArrayList STRESS_IDS = new IntArrayList();
    private static final int CHEST_LAYERS = 24 * 16;
    // Notify clients + known-shape; NO UPDATE_NEIGHBORS:
    // 49k neighbour-update cascades would dominate placement.
    private static final int CHEST_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    // Server-thread-confined: written by placeChests, read + cleared by restoreChests, both via server.execute.
    private static final LongArrayList CHEST_POSITIONS = new LongArrayList();
    private static final ObjectArrayList<BlockState> CHEST_PREV = new ObjectArrayList<>();
    private static final LongArrayList FABULOUS_POSITIONS = new LongArrayList();
    private static final ObjectArrayList<BlockState> FABULOUS_PREV = new ObjectArrayList<>();
    private static final IntArrayList FABULOUS_ITEM_IDS = new IntArrayList();
    // Negative range so locally-spawned ghost ids can never collide with server-assigned entity ids.
    private static int nextStressId = -1_000_000_000;
    @Nullable
    private static volatile ResourceKey<Level> chestDimension;
    private static boolean fabulousRainSet;
    @Nullable
    private static volatile ResourceKey<Level> fabulousDimension;

    private FlwStress() {
    }

    public static Component spawn(int count, String typeArg, double spacing) {
        if (count < 1 || count > STRESS_MAX) {
            return Component.literal("Count out of range (1-" + STRESS_MAX + "): " + count);
        }
        Identifier typeId = Identifier.tryParse(typeArg);
        if (typeId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) {
            return Component.literal("Unknown entity type: " + typeArg);
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(typeId);

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return Component.literal("No world loaded.");
        }

        int side = (int) Math.ceil(Math.cbrt(count));
        int layerSize = side * side;
        double half = (side - 1) * spacing * 0.5D;
        double baseX = mc.player.getX();
        double baseY = mc.player.getY();
        double baseZ = mc.player.getZ();
        RandomSource random = level.getRandom();

        for (int i = 0; i < count; i++) {
            Entity entity = type.create(level, EntitySpawnReason.LOAD);
            if (entity == null) {
                return Component.literal("Could not create entity type: " + typeId);
            }
            float yaw = random.nextFloat() * 360.0F - 180.0F;
            entity.snapTo(
                    baseX - half + (i % side) * spacing,
                    baseY + (i / layerSize) * spacing,
                    baseZ - half + (i / side % side) * spacing,
                    yaw, 0.0F);
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = yaw;
                living.yBodyRotO = yaw;
                living.yHeadRot = yaw;
                living.yHeadRotO = yaw;
            }
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
            }
            entity.noPhysics = true;
            int id = nextStressId--;
            entity.setId(id);
            level.addEntity(entity);
            STRESS_IDS.add(id);
        }
        return Component.literal("Spawned " + count + " client-side " + typeId + " (tracked: " + STRESS_IDS.size()
                + "). Remove with /flywheel stress clear.");
    }

    public static Component spawnChests() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return Component.literal("Chest stress is single-player only (needs the integrated server).");
        }
        LocalPlayer player = mc.player;
        Level clientLevel = player.level();
        ResourceKey<Level> dim = clientLevel.dimension();
        int originX = (Mth.floor(player.getX()) >> 4) << 4;
        int originZ = (Mth.floor(player.getZ()) >> 4) << 4;
        int minY = clientLevel.getMinY();
        int layers = Math.min(CHEST_LAYERS, clientLevel.getHeight());

        server.execute(() -> placeChests(server, dim, originX, originZ, minY, layers));
        return Component.literal("Placing " + (128 * layers) + " chests (" + layers + " layers, checkerboard) in chunk "
                + (originX >> 4) + ", " + (originZ >> 4) + " server-side. Remove with /flywheel stress clear.");
    }

    private static void placeChests(MinecraftServer server, ResourceKey<Level> dim,
                                    int originX, int originZ, int minY, int layers) {
        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            return;
        }
        BlockState chest = Blocks.CHEST.defaultBlockState();
        chestDimension = dim;
        for (int dy = 0; dy < layers; dy++) {
            int y = minY + dy;
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    if (((dx + dz) & 1) != 0) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(originX + dx, y, originZ + dz);
                    CHEST_POSITIONS.add(pos.asLong());
                    CHEST_PREV.add(level.getBlockState(pos));
                    level.setBlock(pos, chest, CHEST_FLAGS);
                }
            }
        }
    }

    /**
     * Builds the Improved-Transparency layer-overlap rig: one sightline crossing every content source the Fabulous reroute replays.
     */
    public static Component buildFabulousRig() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return Component.literal("Fabulous rig is single-player only (needs the integrated server).");
        }
        if (fabulousDimension != null) {
            return Component.literal("A Fabulous rig already exists; /flywheel stress clear it first.");
        }
        LocalPlayer player = mc.player;
        ResourceKey<Level> dim = player.level()
                                       .dimension();
        BlockPos feet = player.blockPosition();
        Direction facing = player.getDirection();

        server.execute(() -> placeFabulousRig(server, dim, feet, facing));
        return Component.literal("Building the Fabulous layer rig facing " + facing + ": glass wall -> floating"
                + " glint/translucent items -> campfire smoke -> second glass wall, under rain. Look straight"
                + " through it; let smoke populate ~5s, then /tick freeze for a fully static scene (items,"
                + " smoke, rain, clouds). A/B reference: /flywheel backend off = vanilla Fabulous;"
                + " /tick unfreeze + /flywheel stress clear when done. Clouds thread through if built at cloud"
                + " height (~y192).");
    }

    private static void placeFabulousRig(MinecraftServer server, ResourceKey<Level> dim, BlockPos feet,
                                         Direction facing) {
        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            return;
        }
        fabulousDimension = dim;
        Direction right = facing.getClockWise();

        placeFabulousWall(level, feet, facing, right, 3, Blocks.STAINED_GLASS.pick(DyeColor.CYAN)
                                                                             .defaultBlockState());
        placeFabulousWall(level, feet, facing, right, 9, Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE)
                                                                             .defaultBlockState());

        for (int side = -1; side <= 1; side += 2) {
            BlockPos base = feet.relative(facing, 7)
                                .relative(right, side);
            setFabulousBlock(level, base.below(), Blocks.SMOOTH_STONE.defaultBlockState());
            setFabulousBlock(level, base, (side < 0 ? Blocks.CAMPFIRE : Blocks.SOUL_CAMPFIRE).defaultBlockState());
        }

        ItemStack[] stacks = {
                glintStack(new ItemStack(Items.DIAMOND_SWORD)),
                new ItemStack(Items.STAINED_GLASS.pick(DyeColor.BLUE)),
                glintStack(new ItemStack(Items.ICE)),
                new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.WHITE))};
        BlockPos itemCenter = feet.relative(facing, 5);
        int rx = right.getUnitVec3i()
                      .getX();
        int rz = right.getUnitVec3i()
                      .getZ();
        for (int i = 0; i < stacks.length; i++) {
            double offset = i - (stacks.length - 1) * 0.5;
            ItemEntity item = new ItemEntity(level,
                    itemCenter.getX() + 0.5 + rx * offset,
                    feet.getY() + 1.2,
                    itemCenter.getZ() + 0.5 + rz * offset,
                    stacks[i]);
            item.setNoGravity(true);
            item.setDeltaMovement(Vec3.ZERO);
            item.setUnlimitedLifetime();
            item.setNeverPickUp();
            level.addFreshEntity(item);
            FABULOUS_ITEM_IDS.add(item.getId());
        }

        server.setWeatherParameters(0, 6000, true, false);
        fabulousRainSet = true;
    }

    private static void placeFabulousWall(ServerLevel level, BlockPos feet, Direction facing, Direction right,
                                          int distance, BlockState state) {
        BlockPos center = feet.relative(facing, distance);
        for (int h = 0; h < 4; h++) {
            for (int w = -2; w <= 2; w++) {
                setFabulousBlock(level, center.relative(right, w)
                                              .above(h), state);
            }
        }
    }

    private static void setFabulousBlock(ServerLevel level, BlockPos pos, BlockState state) {
        FABULOUS_POSITIONS.add(pos.asLong());
        FABULOUS_PREV.add(level.getBlockState(pos));
        level.setBlock(pos, state, CHEST_FLAGS);
    }

    private static ItemStack glintStack(ItemStack stack) {
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private static void restoreFabulousRig(MinecraftServer server, ResourceKey<Level> dim) {
        ServerLevel level = server.getLevel(dim);
        if (level != null) {
            for (int i = 0; i < FABULOUS_POSITIONS.size(); i++) {
                level.setBlock(BlockPos.of(FABULOUS_POSITIONS.getLong(i)), FABULOUS_PREV.get(i), CHEST_FLAGS);
            }
            for (int i = 0; i < FABULOUS_ITEM_IDS.size(); i++) {
                Entity entity = level.getEntity(FABULOUS_ITEM_IDS.getInt(i));
                if (entity != null) {
                    entity.discard();
                }
            }
        }
        FABULOUS_POSITIONS.clear();
        FABULOUS_PREV.clear();
        FABULOUS_ITEM_IDS.clear();
        if (fabulousRainSet) {
            server.setWeatherParameters(6000, 0, false, false);
            fabulousRainSet = false;
        }
        fabulousDimension = null;
    }

    private static void restoreChests(MinecraftServer server, ResourceKey<Level> dim) {
        ServerLevel level = server.getLevel(dim);
        if (level != null) {
            for (int i = 0; i < CHEST_POSITIONS.size(); i++) {
                level.setBlock(BlockPos.of(CHEST_POSITIONS.getLong(i)), CHEST_PREV.get(i), CHEST_FLAGS);
            }
        }
        CHEST_POSITIONS.clear();
        CHEST_PREV.clear();
        chestDimension = null;
    }

    public static Component clear() {
        ClientLevel level = Minecraft.getInstance().level;
        int removed = 0;
        if (level != null) {
            for (int i = 0; i < STRESS_IDS.size(); i++) {
                int id = STRESS_IDS.getInt(i);
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    level.removeEntity(id, Entity.RemovalReason.DISCARDED);
                    removed++;
                }
            }
        }
        STRESS_IDS.clear();

        ResourceKey<Level> dim = chestDimension;
        boolean restoringChests = false;
        if (dim != null) {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> restoreChests(server, dim));
                restoringChests = true;
            }
        }
        ResourceKey<Level> fabDim = fabulousDimension;
        boolean restoringFabulous = false;
        if (fabDim != null) {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> restoreFabulousRig(server, fabDim));
                restoringFabulous = true;
            }
        }
        return Component.literal("Removed " + removed + " stress entities"
                + (restoringChests ? "; restoring checkerboard chests server-side" : "")
                + (restoringFabulous ? "; removing the Fabulous rig server-side" : "") + ".");
    }

    public static Stream<Identifier> entityTypeIds() {
        return BuiltInRegistries.ENTITY_TYPE.keySet().stream();
    }
}
