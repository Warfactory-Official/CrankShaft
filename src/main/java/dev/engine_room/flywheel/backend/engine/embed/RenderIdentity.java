package dev.engine_room.flywheel.backend.engine.embed;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.property.IExtendedBlockState;
import org.jspecify.annotations.Nullable;

public record RenderIdentity(int kind, @Nullable IBlockState block, @Nullable ResourceLocation entity,
                             boolean localPlayer) {
    public static RenderIdentity of(TileEntity blockEntity) {
        var state = blockEntity.getWorld().getBlockState(blockEntity.getPos());
        if (state instanceof IExtendedBlockState extended) state = extended.getClean();
        return new RenderIdentity(TaggedEnvironment.KIND_BLOCK_ENTITY, state, null, false);
    }

    public static RenderIdentity of(Entity entity) {
        var type = entity instanceof EntityPlayer ? new ResourceLocation("minecraft", "player") : EntityList.getKey(
                entity);
        return new RenderIdentity(TaggedEnvironment.KIND_ENTITY, null, type, entity == Minecraft.getMinecraft().player);
    }
}
