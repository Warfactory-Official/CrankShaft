package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.SpecialDates;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;


final class ContainerVisualModels {
    private static final Material CHEST_MATERIAL = SimpleMaterial.builder()
                                                                 .cutout(CutoutShaders.ONE_TENTH)
                                                                 .texture(Sheets.CHEST_SHEET)
                                                                 .mipmap(false)
                                                                 .build();
    private static final Material SHULKER_MATERIAL = SimpleMaterial.builder()
                                                                   .cutout(CutoutShaders.ONE_TENTH)
                                                                   .texture(Sheets.SHULKER_SHEET)
                                                                   .mipmap(false)
                                                                   .backfaceCulling(false)
                                                                   .build();
    private static final Set<String> SHULKER_PATHS_TO_PRUNE = Set.of("/head");

    private ContainerVisualModels() {
    }

    static ModelTree chestModel(BlockState state) {
        var block = state.getBlock();
        return chestModel(state, block instanceof EnderChestBlock, block instanceof TrappedChestBlock);
    }

    static ModelTree chestModel(BlockState state, boolean enderChest, boolean trappedChest) {
        ChestType type = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
        var layer = switch (type) {
            case SINGLE -> ModelLayers.CHEST;
            case LEFT -> ModelLayers.DOUBLE_CHEST_LEFT;
            case RIGHT -> ModelLayers.DOUBLE_CHEST_RIGHT;
        };
        var materialType = chestMaterialType(state.getBlock(), enderChest, trappedChest);
        var sprite = Minecraft.getInstance().getAtlasManager().get(Sheets.chooseSprite(materialType, type));
        return ModelTrees.of(layer, sprite, CHEST_MATERIAL);
    }

    private static ChestRenderState.ChestMaterialType chestMaterialType(Block block, boolean enderChest,
                                                                        boolean trappedChest) {
        if (block instanceof CopperChestBlock copperChest) {
            return switch (copperChest.getState()) {
                case UNAFFECTED -> ChestRenderState.ChestMaterialType.COPPER_UNAFFECTED;
                case EXPOSED -> ChestRenderState.ChestMaterialType.COPPER_EXPOSED;
                case WEATHERED -> ChestRenderState.ChestMaterialType.COPPER_WEATHERED;
                case OXIDIZED -> ChestRenderState.ChestMaterialType.COPPER_OXIDIZED;
            };
        }
        if (enderChest) {
            return ChestRenderState.ChestMaterialType.ENDER_CHEST;
        }
        if (SpecialDates.isExtendedChristmas()) {
            return ChestRenderState.ChestMaterialType.CHRISTMAS;
        }
        return trappedChest ? ChestRenderState.ChestMaterialType.TRAPPED : ChestRenderState.ChestMaterialType.REGULAR;
    }

    static Matrix4f chestPose(BlockState state, BlockPos position) {
        float horizontalAngle = state.getValue(ChestBlock.FACING).toYRot();
        return new Matrix4f().translate(position.getX(), position.getY(), position.getZ())
                             .translate(0.5f, 0.5f, 0.5f)
                             .rotateY(-horizontalAngle * Mth.DEG_TO_RAD)
                             .translate(-0.5f, -0.5f, -0.5f);
    }

    static ModelTree shulkerModel(@Nullable DyeColor color) {
        var spriteId = color == null ? Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION : Sheets.getShulkerBoxSprite(color);
        var sprite = Minecraft.getInstance().getAtlasManager().get(spriteId);
        return ModelTrees.of(ModelLayers.SHULKER, SHULKER_PATHS_TO_PRUNE, sprite, SHULKER_MATERIAL);
    }

    static Matrix4f shulkerPose(BlockState state, BlockPos position) {
        Direction direction = state.getBlock() instanceof ShulkerBoxBlock ? state.getValue(
                ShulkerBoxBlock.FACING) : Direction.UP;
        return new Matrix4f().translate(position.getX(), position.getY(), position.getZ())
                             .translate(0.5f, 0.5f, 0.5f)
                             .scale(0.9995f)
                             .rotate(direction.getRotation())
                             .scale(1, -1, -1)
                             .translate(0, -1, 0);
    }
}
