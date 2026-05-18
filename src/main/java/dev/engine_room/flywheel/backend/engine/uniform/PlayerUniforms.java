package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.lib.compat.FallFlyingProviders;
import dev.engine_room.flywheel.lib.compat.PlayerCompat;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EnumSkyBlock;
import org.jspecify.annotations.Nullable;

public final class PlayerUniforms extends UniformWriter {
    private static final int SIZE = 16 * 2 + 8 + 4 * 9;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.PLAYER_INDEX, SIZE);

    private PlayerUniforms() {
    }

    public static void update(RenderContext context) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) {
            BUFFER.clear();
            return;
        }

        long ptr = BUFFER.ptr();

        NetworkPlayerInfo info = Minecraft.getMinecraft().getConnection() != null
                ? Minecraft.getMinecraft().getConnection().getPlayerInfo(player.getUniqueID())
                : null;

        Vec3d eyePos = player.getPositionEyes(context.partialTick());
        ptr = writeVec3(ptr, (float) eyePos.x, (float) eyePos.y, (float) eyePos.z);

        ptr = writeTeamColor(ptr, info == null ? null : info.getPlayerTeam());

        ptr = writeEyeBrightness(ptr, player);

        ptr = writeFloat(ptr, heldLight(player) / 15f);
        ptr = writeEyeIn(ptr, player, eyePos);

        ptr = writeInt(ptr, player.isSneaking() ? 1 : 0);
        ptr = writeInt(ptr, player.isPlayerSleeping() ? 1 : 0);
        ptr = writeInt(ptr, PlayerCompat.isSwimming(player) ? 1 : 0);
        ptr = writeInt(ptr, FallFlyingProviders.test(player) ? 1 : 0);

        ptr = writeInt(ptr, Minecraft.getMinecraft().gameSettings.keyBindSneak.isKeyDown() ? 1 : 0);

        ptr = writeInt(ptr, info == null ? 0 : info.getGameType().getID());

        BUFFER.markDirty();
    }

    private static long writeEyeIn(long ptr, EntityPlayerSP player, Vec3d eyePos) {
        return writeInFluidAndBlock(ptr, player.world, new BlockPos(eyePos), eyePos);
    }

    private static long writeTeamColor(long ptr, @Nullable ScorePlayerTeam team) {
        if (team != null) {
            int color = team.getColor().getColorIndex();
            int red = (color >> 16) & 0xFF;
            int green = (color >> 8) & 0xFF;
            int blue = color & 0xFF;
            return writeVec4(ptr, red / 255f, green / 255f, blue / 255f, 1f);
        } else {
            return writeVec4(ptr, 1f, 1f, 1f, 0f);
        }
    }

    // Despite the slot name `flw_eyeBrightness`, both we and upstream sample at
    // `Entity.getPosition()` (feet block), not the eye block. Upstream's writeEyeBrightness does
    // `level.getBrightness(LightLayer.BLOCK, player.blockPosition())` — `blockPosition()` is the
    // feet pivot. Mismatch with the uniform's name is inherited; do not "fix" without coordinating
    // a rename of the GLSL slot (would break downstream shader consumers).
    private static long writeEyeBrightness(long ptr, EntityPlayerSP player) {
        int blockBrightness = player.world.getLightFor(EnumSkyBlock.BLOCK, player.getPosition());
        int skyBrightness = player.world.getLightFor(EnumSkyBlock.SKY, player.getPosition());
        int maxBrightness = 15;

        return writeVec2(ptr, blockBrightness / (float) maxBrightness, skyBrightness / (float) maxBrightness);
    }

    /** {@code EnumHand.values()} clones on every call; cache once and reuse. Never mutate. */
    private static final EnumHand[] HANDS = EnumHand.values();

    // Upstream parity: only ItemBlock-held stacks contribute. Upstream's writeHeldLight gates on
    // `handItem instanceof BlockItem` and reads `block.defaultBlockState()`'s light emission via
    // the xplat hook. Non-block light-emitting items (modded handheld lanterns, glow staffs) are
    // intentionally ignored — a "held light from arbitrary item" hook would need a new registry
    // and isn't part of the upstream contract.
    private static int heldLight(EntityPlayerSP player) {
        int heldLight = 0;
        BlockPos pos = player.getPosition();
        for (EnumHand hand : HANDS) {
            ItemStack stack = player.getHeldItem(hand);
            if (stack.getItem() instanceof ItemBlock blockItem) {
                Block block = blockItem.getBlock();
                int blockLight = block.getDefaultState().getLightValue(player.world, pos);
                if (heldLight < blockLight) {
                    heldLight = blockLight;
                }
            }
        }
        return heldLight;
    }

}
