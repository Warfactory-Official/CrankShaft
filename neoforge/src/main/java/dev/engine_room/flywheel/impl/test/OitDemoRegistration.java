package dev.engine_room.flywheel.impl.test;

import java.util.Set;
import java.util.function.UnaryOperator;

import dev.engine_room.flywheel.api.Flywheel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class OitDemoRegistration {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Flywheel.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Flywheel.ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Flywheel.ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Flywheel.ID);

    private static final UnaryOperator<BlockBehaviour.Properties> PROPS = p -> p.strength(0.3f)
            .noOcclusion();

    public static final DeferredBlock<BlockOitDemoGlass> GLASS =
            BLOCKS.registerBlock("oit_demo_glass", BlockOitDemoGlass::new, PROPS);
    public static final DeferredBlock<BlockOitDemoGlassPane> GLASS_PANE =
            BLOCKS.registerBlock("oit_demo_glass_pane", BlockOitDemoGlassPane::new, PROPS);
    public static final DeferredBlock<BlockOitDemoStainedGlass> STAINED_GLASS =
            BLOCKS.registerBlock("oit_demo_stained_glass", BlockOitDemoStainedGlass::new, PROPS);
    public static final DeferredBlock<BlockOitDemoStainedGlassPane> STAINED_GLASS_PANE =
            BLOCKS.registerBlock("oit_demo_stained_glass_pane", BlockOitDemoStainedGlassPane::new, PROPS);

    public static final DeferredItem<BlockItem> GLASS_ITEM = ITEMS.registerSimpleBlockItem(GLASS);
    public static final DeferredItem<BlockItem> GLASS_PANE_ITEM = ITEMS.registerSimpleBlockItem(GLASS_PANE);
    public static final DeferredItem<BlockItem> STAINED_GLASS_ITEM = ITEMS.registerSimpleBlockItem(STAINED_GLASS);
    public static final DeferredItem<BlockItem> STAINED_GLASS_PANE_ITEM = ITEMS.registerSimpleBlockItem(STAINED_GLASS_PANE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TileEntityOitDemo>> OIT_DEMO_BE =
            BLOCK_ENTITIES.register("oit_demo", () -> {
                BlockEntityType<TileEntityOitDemo> type = new BlockEntityType<>(TileEntityOitDemo::new,
                        Set.of(GLASS.get(), GLASS_PANE.get(), STAINED_GLASS.get(), STAINED_GLASS_PANE.get()));
                OitDemoContent.OIT_DEMO_BE = type;
                return type;
            });

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("oit_demo", () ->
            CreativeModeTab.builder()
                    .title(Component.literal("Flywheel OIT Demo"))
                    .icon(() -> new ItemStack(STAINED_GLASS_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(GLASS_ITEM.get());
                        output.accept(GLASS_PANE_ITEM.get());
                        for (DyeColor color : DyeColor.values()) {
                            output.accept(stainedStack(STAINED_GLASS_ITEM.get(), color));
                        }
                        for (DyeColor color : DyeColor.values()) {
                            output.accept(stainedStack(STAINED_GLASS_PANE_ITEM.get(), color));
                        }
                    })
                    .build());

    private OitDemoRegistration() {
    }

    private static ItemStack stainedStack(Item item, DyeColor color) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(BlockOitDemoStained.COLOR, color));
        return stack;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
