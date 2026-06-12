package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

public final class PlayerUniforms extends UniformWriter {
    private static final int SIZE = 16 * 2 + 8 + 4 * 9;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.PLAYER_INDEX, SIZE);
    private static final InteractionHand[] HANDS = InteractionHand.values();

    private PlayerUniforms() {
    }

    public static void update(RenderContext context) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            BUFFER.clear();
            return;
        }

        long ptr = BUFFER.ptr();

        PlayerInfo info = Minecraft.getInstance().getConnection() != null
                ? Minecraft.getInstance().getConnection().getPlayerInfo(player.getUUID())
                : null;

        Vec3 eyePos = player.getEyePosition(context.partialTick());
        ptr = writeVec3(ptr, (float) eyePos.x, (float) eyePos.y, (float) eyePos.z);

        ptr = writeTeamColor(ptr, info == null ? null : info.getTeam());

        ptr = writeEyeBrightness(ptr, player);

        ptr = writeFloat(ptr, heldLight(player) / 15f);
        ptr = writeEyeIn(ptr, player, eyePos);

        ptr = writeInt(ptr, player.isCrouching() ? 1 : 0);
        ptr = writeInt(ptr, player.isSleeping() ? 1 : 0);
        ptr = writeInt(ptr, player.isSwimming() ? 1 : 0);
        ptr = writeInt(ptr, player.isFallFlying() ? 1 : 0);

        ptr = writeInt(ptr, player.isShiftKeyDown() ? 1 : 0);

        ptr = writeInt(ptr, info == null ? 0 : info.getGameMode().getId());

        BUFFER.markDirty();
    }

    private static long writeEyeIn(long ptr, LocalPlayer player, Vec3 eyePos) {
        return writeInFluidAndBlock(ptr, player.level(), BlockPos.containing(eyePos), eyePos);
    }

    private static long writeTeamColor(long ptr, @Nullable PlayerTeam team) {
        if (team != null) {
            Optional<TeamColor> teamColor = team.getColor();
            if (teamColor.isPresent()) {
                int color = teamColor.get().rgb();
                int red = ARGB.red(color);
                int green = ARGB.green(color);
                int blue = ARGB.blue(color);
                return writeVec4(ptr, red / 255f, green / 255f, blue / 255f, 1f);
            } else {
                return writeVec4(ptr, 1f, 1f, 1f, 1f);
            }
        } else {
            return writeVec4(ptr, 1f, 1f, 1f, 0f);
        }
    }

    private static long writeEyeBrightness(long ptr, LocalPlayer player) {
        int blockBrightness = player.level().getBrightness(LightLayer.BLOCK, player.blockPosition());
        int skyBrightness = player.level().getBrightness(LightLayer.SKY, player.blockPosition());
        int maxBrightness = 15;

        return writeVec2(ptr, blockBrightness / (float) maxBrightness, skyBrightness / (float) maxBrightness);
    }

    private static int heldLight(LocalPlayer player) {
        int heldLight = 0;
        for (InteractionHand hand : HANDS) {
            if (player.getItemInHand(hand).getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                int blockLight = block.defaultBlockState().getLightEmission();
                if (heldLight < blockLight) {
                    heldLight = blockLight;
                }
            }
        }
        return heldLight;
    }

}
