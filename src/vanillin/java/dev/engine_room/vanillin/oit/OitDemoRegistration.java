package dev.engine_room.vanillin.oit;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.vanillin.VanillaVisuals;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemCloth;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/**
 * Dev-only stained-glass demo blocks used to validate the chunk-OIT replay path and (when the OF
 * shader backend is present) the OF translucent-cube dispatch. Gated on
 * {@link FMLLaunchHandler#isDeobfuscatedEnvironment()} — auto-enabled in dev workspaces, skipped
 * in obfuscated (production) launches so release jars don't register the four {@link Block}
 * instances.
 */
@Mod.EventBusSubscriber(modid = Tags.VANILLIN_ID)
public final class OitDemoRegistration {
    private static final boolean ENABLED = FMLLaunchHandler.isDeobfuscatedEnvironment();

    private static final ResourceLocation GLASS_ID = new ResourceLocation(Tags.VANILLIN_ID, "oit_demo_glass");
    private static final ResourceLocation GLASS_PANE_ID = new ResourceLocation(Tags.VANILLIN_ID, "oit_demo_glass_pane");
    private static final ResourceLocation STAINED_GLASS_ID = new ResourceLocation(Tags.VANILLIN_ID, "oit_demo_stained_glass");
    private static final ResourceLocation STAINED_GLASS_PANE_ID = new ResourceLocation(Tags.VANILLIN_ID, "oit_demo_stained_glass_pane");

    // CreativeTabs self-registers into CreativeTabs.CREATIVE_TAB_ARRAY at construction, which is
    // visible as an empty tab in production if blocks are never registered. Gate the construction
    // on ENABLED so production never sees this tab.
    private static final CreativeTabs CREATIVE_TAB = ENABLED ? new CreativeTabs(Tags.VANILLIN_ID + ".oit_demo") {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(Blocks.GLASS);
        }
    } : null;

    public static final Block GLASS = new BlockOitDemoGlass()
            .setRegistryName(GLASS_ID)
            .setTranslationKey(Tags.VANILLIN_ID + ".oit_demo_glass")
            .setCreativeTab(CREATIVE_TAB);
    public static final Block GLASS_PANE = new BlockOitDemoGlassPane()
            .setRegistryName(GLASS_PANE_ID)
            .setTranslationKey(Tags.VANILLIN_ID + ".oit_demo_glass_pane")
            .setCreativeTab(CREATIVE_TAB);
    public static final Block STAINED_GLASS = new BlockOitDemoStainedGlass()
            .setRegistryName(STAINED_GLASS_ID)
            .setTranslationKey(Tags.VANILLIN_ID + ".oit_demo_stained_glass")
            .setCreativeTab(CREATIVE_TAB);
    public static final Block STAINED_GLASS_PANE = new BlockOitDemoStainedGlassPane()
            .setRegistryName(STAINED_GLASS_PANE_ID)
            .setTranslationKey(Tags.VANILLIN_ID + ".oit_demo_stained_glass_pane")
            .setCreativeTab(CREATIVE_TAB);

    private OitDemoRegistration() {
    }

    public static void preInit(FMLPreInitializationEvent event) {
        if (!ENABLED) return;
        GameRegistry.registerTileEntity(TileEntityOitDemo.class, new ResourceLocation(Tags.VANILLIN_ID, "oit_demo"));
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityOitDemo.class, new OitDemoRenderer());
        VanillaVisuals.blockEntity(TileEntityOitDemo.class)
                .factory(OitDemoVisual::new)
                .apply(VanillaVisuals.STABLE);
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        if (!ENABLED) return;
        event.getRegistry().registerAll(GLASS, GLASS_PANE, STAINED_GLASS, STAINED_GLASS_PANE);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        if (!ENABLED) return;
        event.getRegistry().registerAll(
                new ItemBlock(GLASS).setRegistryName(GLASS_ID),
                new ItemBlock(GLASS_PANE).setRegistryName(GLASS_PANE_ID),
                new ItemCloth(STAINED_GLASS).setRegistryName(STAINED_GLASS_ID),
                new ItemCloth(STAINED_GLASS_PANE).setRegistryName(STAINED_GLASS_PANE_ID)
        );
    }

    // Blocks render ENTITYBLOCK_ANIMATED (no chunk geometry); map every state to a real vanilla
    // MRL so the model registry baking pass doesn't log "missing blockstate" warnings. Never
    // consulted at draw time.
    private static final ModelResourceLocation PLACEHOLDER =
            new ModelResourceLocation("minecraft:stained_glass", "color=white");

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        if (!ENABLED) return;
        StateMapperBase placeholder = new StateMapperBase() {
            @Override
            protected ModelResourceLocation getModelResourceLocation(IBlockState state) {
                return PLACEHOLDER;
            }
        };
        ModelLoader.setCustomStateMapper(GLASS, placeholder);
        ModelLoader.setCustomStateMapper(GLASS_PANE, placeholder);
        ModelLoader.setCustomStateMapper(STAINED_GLASS, placeholder);
        ModelLoader.setCustomStateMapper(STAINED_GLASS_PANE, placeholder);

        // Route item models to vanilla per-color inventory MRLs — mirrors
        // RenderItem.registerBlock(Blocks.STAINED_GLASS, meta, "<color>_stained_glass") which
        // registers as ModelResourceLocation("<color>_stained_glass", "inventory").
        registerInventoryItemModel(Item.getItemFromBlock(GLASS), "minecraft:glass");
        registerInventoryItemModel(Item.getItemFromBlock(GLASS_PANE), "minecraft:glass_pane");
        registerStainedInventoryItemModels(Item.getItemFromBlock(STAINED_GLASS), "stained_glass");
        registerStainedInventoryItemModels(Item.getItemFromBlock(STAINED_GLASS_PANE), "stained_glass_pane");
    }

    private static void registerInventoryItemModel(Item item, String mrlId) {
        ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(mrlId, "inventory"));
    }

    private static void registerStainedInventoryItemModels(Item item, String suffix) {
        for (EnumDyeColor color : EnumDyeColor.values()) {
            ModelResourceLocation mrl = new ModelResourceLocation(
                    "minecraft:" + color.getName() + "_" + suffix, "inventory");
            ModelLoader.setCustomModelResourceLocation(item, color.getMetadata(), mrl);
        }
    }
}
