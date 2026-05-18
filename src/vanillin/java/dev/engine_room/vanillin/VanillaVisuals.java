package dev.engine_room.vanillin;

import dev.engine_room.flywheel.lib.compose.*;
import dev.engine_room.flywheel.lib.visual.ItemVisual;
import dev.engine_room.vanillin.config.BlockEntityVisualizerBuilder;
import dev.engine_room.vanillin.config.Configurator;
import dev.engine_room.vanillin.config.EntityVisualizerBuilder;
import dev.engine_room.vanillin.elements.ShadowElement;
import dev.engine_room.vanillin.visuals.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class VanillaVisuals {
    public static final Configurator CONFIGURATOR = new Configurator();

    // Stable visuals are enabled by default always.
    public static final boolean STABLE = true;
    // Experimental visuals are enabled by default in dev.
    public static final boolean EXPERIMENTAL = FMLLaunchHandler.isDeobfuscatedEnvironment();

    private VanillaVisuals() {
    }

    public static void init() {
        blockEntity(TileEntityChest.class).factory(ChestVisual::new).apply(STABLE);
        blockEntity(TileEntityEnderChest.class).factory(EnderChestVisual::new).apply(STABLE);
        blockEntity(TileEntityShulkerBox.class).factory(ShulkerBoxVisual::new).apply(STABLE);

        minecart(EntityMinecartEmpty.class).apply(STABLE);
        minecart(EntityMinecartChest.class).apply(STABLE);
        minecart(EntityMinecartFurnace.class).apply(STABLE);
        minecart(EntityMinecartHopper.class).apply(STABLE);
        minecart(EntityMinecartCommandBlock.class).apply(STABLE);

        composable(EntityMinecartTNT.class).apply(VanillaVisuals::commonElements)
                .with(element(VisualElements.SHADOW).configure(new ShadowElement.Config(0.7F, ShadowElement.Config.DEFAULT_STRENGTH)).build())
                .with(element(VisualElements.FIRE).build())
                .with(element(VisualElements.TNT_MINECART).build())
                .build()
                .skipVanillaRender(MinecartVisual::shouldSkipRender)
                .apply(STABLE);

        itemFrame(EntityItemFrame.class).apply(STABLE);

        composable(EntityItem.class)
                .with(element(VisualElements.ITEM_ENTITY).build())
                .shouldVisualize((ctx, entity) -> ItemVisual.isSupported(entity))
                .build()
                .skipVanillaRender(ItemVisual::isSupported)
                .apply(STABLE);
    }

    // Upstream's overloaded `builder(BlockEntityType)`/`builder(EntityType)` collapse to the
    // same erased Class<T> signature in 1.12.2, so split into distinct method names.
    public static <T extends TileEntity> BlockEntityVisualizerBuilder<T> blockEntity(Class<T> type) {
        return new BlockEntityVisualizerBuilder<>(CONFIGURATOR, type);
    }

    public static <T extends Entity> EntityVisualizerBuilder<T> entity(Class<T> type) {
        return new EntityVisualizerBuilder<>(CONFIGURATOR, type);
    }

    public static <T extends Entity> EntityBuilder<T> composable(Class<T> entityType) {
        return new EntityBuilder<>(entityType);
    }

    public static <T, C> ConfiguredElementImpl.ConfiguredElementBuilder<T, C> element(VisualElement<T, C> element) {
        return new ConfiguredElementImpl.ConfiguredElementBuilder<>(element);
    }

    public static <T extends Entity> void commonElements(EntityBuilder<T> builder) {
        builder.with(element(VisualElements.HITBOX).configure(false).build());
    }

    public static <T extends EntityItemFrame> EntityVisualizerBuilder<T> itemFrame(Class<T> type) {
        return composable(type).apply(VanillaVisuals::commonElements)
                // CrankShaft divergence: render fire on briefly-burning item frames; upstream omits this.
                .with(element(VisualElements.FIRE).build())
                .with(element(VisualElements.ITEM_FRAME).build())
                .shouldVisualize((ctx, entity) -> ItemFrameVisual.shouldVisualize(entity))
                .build()
                .skipVanillaRender(ItemFrameVisual::shouldVisualize);
    }

    public static <T extends EntityMinecart> EntityVisualizerBuilder<T> minecart(Class<T> type) {
        return composable(type).apply(VanillaVisuals::commonElements)
                .with(element(VisualElements.SHADOW).configure(new ShadowElement.Config(0.7F, ShadowElement.Config.DEFAULT_STRENGTH)).build())
                .with(element(VisualElements.FIRE).build())
                .with(element(VisualElements.MINECART).build())
                .build()
                .skipVanillaRender(MinecartVisual::shouldSkipRender);
    }

    public static final class EntityBuilder<T extends Entity> {
        private final List<ConfiguredElement<? super T>> elements = new ArrayList<>();
        private final Class<T> entityType;
        @Nullable
        private VisualizationPredicate<T> predicate;

        public EntityBuilder(Class<T> entityType) {
            this.entityType = entityType;
        }

        /**
         * Set a predicate to control whether <em>all</em> elements are visualized.
         * <p>This is useful when you can't guarantee than an entity will support visualization for its entire lifetime.
         *
         * @param predicate A visualization predicate, returning {@code true} to indicate the entity should be visualized.
         */
        public EntityBuilder<T> shouldVisualize(VisualizationPredicate<T> predicate) {
            this.predicate = predicate;
            return this;
        }

        /**
         * Add a configured visual element to this visualizer.
         *
         * @param element The configured visual element.
         */
        public EntityBuilder<T> with(ConfiguredElement<? super T> element) {
            elements.add(element);
            return this;
        }

        public EntityBuilder<T> apply(Consumer<EntityBuilder<T>> mutate) {
            mutate.accept(this);
            return this;
        }

        @SuppressWarnings("unchecked")
        public EntityVisualizerBuilder<T> build() {
            ConfiguredElement<? super T>[] elementsArray = elements.toArray(new ConfiguredElement[0]);
            VisualizationPredicate<T> p = predicate != null ? predicate : VisualizationPredicate.alwaysTrue();
            ComposableEntityVisual.Controller<T> controller = new ComposableEntityVisual.Controller<>(elementsArray, p);
            return entity(entityType).factory((ctx, entity, partialTick) -> new ComposableEntityVisual<>(ctx, entity, partialTick, controller));
        }
    }
}
