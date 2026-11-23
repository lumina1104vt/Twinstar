package com.lumina.twinstar.storage;

import appeng.api.AECapabilities;
import appeng.api.storage.ISubMenuHost;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.menu.implementations.MenuTypeBuilder;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.algorithm.ae2.PatternChances;
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
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Twinstar.MODID);

    /** On an encoded pattern: outputs that only come with some probability (written by gen_patterns). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternChances>> PATTERN_CHANCES =
            COMPONENTS.registerComponentType("pattern_chances",
                    b -> b.persistent(PatternChances.CODEC).networkSynchronized(PatternChances.STREAM_CODEC));
    /** On a hub pattern: the machine faces each material goes in through ("specified" interfaces). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputFaces>> PATTERN_INPUT_FACES =
            COMPONENTS.registerComponentType("pattern_input_faces",
                    b -> b.persistent(PatternInputFaces.CODEC).networkSynchronized(PatternInputFaces.STREAM_CODEC));

    /** The materials and products of a hub pattern whose NBT does not matter. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternIgnoreNbt>> PATTERN_IGNORE_NBT =
            COMPONENTS.registerComponentType("pattern_ignore_nbt",
                    b -> b.persistent(PatternIgnoreNbt.CODEC).networkSynchronized(PatternIgnoreNbt.STREAM_CODEC));

    /** Where each material of a hub pattern goes when not into the machine: its sub-machines, or in front of it. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputTargets>> PATTERN_INPUT_TARGETS =
            COMPONENTS.registerComponentType("pattern_input_targets",
                    b -> b.persistent(PatternInputTargets.CODEC).networkSynchronized(PatternInputTargets.STREAM_CODEC));

    /** The GUI slot each material of a hub pattern goes into, for machines that take items only in a GUI. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputGuiSlots>> PATTERN_INPUT_GUI_SLOTS =
            COMPONENTS.registerComponentType("pattern_input_gui_slots",
                    b -> b.persistent(PatternInputGuiSlots.CODEC).networkSynchronized(PatternInputGuiSlots.STREAM_CODEC));

    /** The GUI slot each product of a hub pattern is taken out of, for machines that give them only in a GUI. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternOutputGuiSlots>> PATTERN_OUTPUT_GUI_SLOTS =
            COMPONENTS.registerComponentType("pattern_output_gui_slots",
                    b -> b.persistent(PatternOutputGuiSlots.CODEC).networkSynchronized(PatternOutputGuiSlots.STREAM_CODEC));

    /** The products of a hub pattern that count as made once the run's materials are all in the machine. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternDispatchDone>> PATTERN_DISPATCH_DONE =
            COMPONENTS.registerComponentType("pattern_dispatch_done",
                    b -> b.persistent(PatternDispatchDone.CODEC).networkSynchronized(PatternDispatchDone.STREAM_CODEC));

    /** What each material of a hub pattern is besides used up: catalyst, catalyst kept for the batch, tool to right-click with. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputRoles>> PATTERN_INPUT_ROLES =
            COMPONENTS.registerComponentType("pattern_input_roles",
                    b -> b.persistent(PatternInputRoles.CODEC).networkSynchronized(PatternInputRoles.STREAM_CODEC));

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
        COMPONENTS.register(bus);
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
