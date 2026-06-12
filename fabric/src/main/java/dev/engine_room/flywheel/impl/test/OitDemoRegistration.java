package dev.engine_room.flywheel.impl.test;

import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import dev.engine_room.flywheel.api.Flywheel;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public final class OitDemoRegistration {
	private static final UnaryOperator<BlockBehaviour.Properties> PROPS = p -> p.strength(0.3f)
			.noOcclusion();

	public static BlockOitDemoGlass glass;
	public static BlockOitDemoGlassPane glassPane;
	public static BlockOitDemoStainedGlass stainedGlass;
	public static BlockOitDemoStainedGlassPane stainedGlassPane;

	public static Item glassItem;
	public static Item glassPaneItem;
	public static Item stainedGlassItem;
	public static Item stainedGlassPaneItem;

	private OitDemoRegistration() {
	}

	public static void register() {
		glass = registerBlock("oit_demo_glass", BlockOitDemoGlass::new);
		glassPane = registerBlock("oit_demo_glass_pane", BlockOitDemoGlassPane::new);
		stainedGlass = registerBlock("oit_demo_stained_glass", BlockOitDemoStainedGlass::new);
		stainedGlassPane = registerBlock("oit_demo_stained_glass_pane", BlockOitDemoStainedGlassPane::new);

		glassItem = registerBlockItem("oit_demo_glass", glass);
		glassPaneItem = registerBlockItem("oit_demo_glass_pane", glassPane);
		stainedGlassItem = registerBlockItem("oit_demo_stained_glass", stainedGlass);
		stainedGlassPaneItem = registerBlockItem("oit_demo_stained_glass_pane", stainedGlassPane);

		BlockEntityType<TileEntityOitDemo> type = new BlockEntityType<>(TileEntityOitDemo::new,
				Set.of(glass, glassPane, stainedGlass, stainedGlassPane));
		OitDemoContent.OIT_DEMO_BE = type;
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.fromNamespaceAndPath(Flywheel.ID, "oit_demo"), type);

		registerTab();
	}

	private static <T extends Block> T registerBlock(String path, Function<BlockBehaviour.Properties, T> factory) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Flywheel.ID, path));
		T block = factory.apply(PROPS.apply(BlockBehaviour.Properties.of()
				.setId(key)));
		Registry.register(BuiltInRegistries.BLOCK, key, block);
		for (BlockState state : block.getStateDefinition()
				.getPossibleStates()) {
			Block.BLOCK_STATE_REGISTRY.add(state);
			state.initCache();
		}
		return block;
	}

	private static Item registerBlockItem(String path, Block block) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Flywheel.ID, path));
		BlockItem item = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix()
				.requiredFeatures(block.requiredFeatures())
				.setId(key));
		item.registerBlocks(Item.BY_BLOCK, item);
		Registry.register(BuiltInRegistries.ITEM, key, item);
		return item;
	}

	private static void registerTab() {
		ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(Flywheel.ID, "oit_demo"));
		CreativeModeTab tab = FabricCreativeModeTab.builder()
				.title(Component.literal("Flywheel OIT Demo"))
				.icon(() -> new ItemStack(stainedGlassItem))
				.displayItems((params, output) -> {
					output.accept(glassItem);
					output.accept(glassPaneItem);
					for (DyeColor color : DyeColor.values()) {
						output.accept(stainedStack(stainedGlassItem, color));
					}
					for (DyeColor color : DyeColor.values()) {
						output.accept(stainedStack(stainedGlassPaneItem, color));
					}
				})
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, tab);
	}

	private static ItemStack stainedStack(Item item, DyeColor color) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(BlockOitDemoStained.COLOR, color));
		return stack;
	}
}
