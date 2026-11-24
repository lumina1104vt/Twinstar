package com.lumina.twinstar.storage;

import appeng.api.AECapabilities;
import appeng.api.storage.ISubMenuHost;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.menu.implementations.MenuTypeBuilder;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.algorithm.ae2.PatternChances;
import com.lumina.twinstar.algorithm.ae2.PatternComponents;
import com.lumina.twinstar.algorithm.ae2.PatternDispatchDone;
import com.lumina.twinstar.algorithm.ae2.PatternInputFaces;
import com.lumina.twinstar.algorithm.ae2.PatternInputGuiSlots;
import com.lumina.twinstar.algorithm.ae2.PatternOutputGuiSlots;
import com.lumina.twinstar.algorithm.ae2.PatternIgnoreNbt;
import com.lumina.twinstar.algorithm.ae2.PatternInputRoles;
import com.lumina.twinstar.algorithm.ae2.PatternInputTargets;
import com.lumina.twinstar.order.OrderMenu;
import com.lumina.twinstar.order.PlanMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The lib's own registrations: the BigInteger test storage, the order menus and the pattern chances component. */
public final class TwinstarContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Twinstar.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Twinstar.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Twinstar.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Twinstar.MODID);

    // the pattern components live in the pattern crafting module; kept here under their old names
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternChances>> PATTERN_CHANCES = PatternComponents.PATTERN_CHANCES;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputFaces>> PATTERN_INPUT_FACES = PatternComponents.PATTERN_INPUT_FACES;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternIgnoreNbt>> PATTERN_IGNORE_NBT = PatternComponents.PATTERN_IGNORE_NBT;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputTargets>> PATTERN_INPUT_TARGETS = PatternComponents.PATTERN_INPUT_TARGETS;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputGuiSlots>> PATTERN_INPUT_GUI_SLOTS = PatternComponents.PATTERN_INPUT_GUI_SLOTS;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternOutputGuiSlots>> PATTERN_OUTPUT_GUI_SLOTS = PatternComponents.PATTERN_OUTPUT_GUI_SLOTS;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternDispatchDone>> PATTERN_DISPATCH_DONE = PatternComponents.PATTERN_DISPATCH_DONE;
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputRoles>> PATTERN_INPUT_ROLES = PatternComponents.PATTERN_INPUT_ROLES;

    /** The twin-star order screen (replaces AE2's amount screen on grids with a hub). */
    public static final DeferredHolder<MenuType<?>, MenuType<OrderMenu>> ORDER_MENU = MENUS.register("order",
            () -> MenuTypeBuilder.create(OrderMenu::new, ISubMenuHost.class)
                    .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "order")));
    /** The twin-star plan screen (in place of AE2's confirm screen for orders from the order screen). */
    public static final DeferredHolder<MenuType<?>, MenuType<PlanMenu>> PLAN_MENU = MENUS.register("plan",
            () -> MenuTypeBuilder.create(PlanMenu::new, ISubMenuHost.class)
                    .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "plan")));

    public static final DeferredBlock<BigStorageBlock> BIG_STORAGE = BLOCKS.registerBlock("big_storage", BigStorageBlock::new,
            BlockBehaviour.Properties.of().strength(2.2f, 11f).sound(SoundType.METAL));
    public static final DeferredItem<BlockItem> BIG_STORAGE_ITEM = ITEMS.registerSimpleBlockItem("big_storage", BIG_STORAGE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BigStorageBlockEntity>> BIG_STORAGE_BE =
            BLOCK_ENTITIES.register("big_storage", () -> {
                var type = BlockEntityType.Builder.of(BigStorageBlockEntity::new, BIG_STORAGE.get()).build(null);
                BIG_STORAGE.get().setBlockEntity(BigStorageBlockEntity.class, type, null, null);
                AEBaseBlockEntity.registerBlockEntityItem(type, BIG_STORAGE.get().asItem());
                return type;
            });

    private TwinstarContent() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        PatternComponents.register(bus);
        bus.addListener(TwinstarContent::registerCapabilities);
        bus.addListener(TwinstarContent::addToCreativeTab);
    }

    /** AE2 only finds a neighbour's grid node through this capability (otherwise cables don't connect). */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, BIG_STORAGE_BE.get(), (be, ctx) -> be);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS)
            event.accept(BIG_STORAGE_ITEM);
    }
}
