package com.lumina.twinstar.storage;

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
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The lib's own registrations: the BigInteger test storage, the order menus, and the pattern data (1.20.1 has no data
 * components, so each piece of data on a pattern is a {@link PatternData}: NBT on the pattern's stack).
 */
public final class TwinstarContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Twinstar.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Twinstar.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Twinstar.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, Twinstar.MODID);

    /** On an encoded pattern: outputs that only come with some probability (written by gen_patterns). */
    public static final PatternData<PatternChances> PATTERN_CHANCES = PatternComponents.PATTERN_CHANCES;
    /** On a hub pattern: the machine faces each material goes in through ("specified" interfaces). */
    public static final PatternData<PatternInputFaces> PATTERN_INPUT_FACES = PatternComponents.PATTERN_INPUT_FACES;
    /** The materials and products of a hub pattern whose NBT does not matter. */
    public static final PatternData<PatternIgnoreNbt> PATTERN_IGNORE_NBT = PatternComponents.PATTERN_IGNORE_NBT;
    /** Where each material of a hub pattern goes when not into the machine: its sub-machines, or in front of it. */
    public static final PatternData<PatternInputTargets> PATTERN_INPUT_TARGETS = PatternComponents.PATTERN_INPUT_TARGETS;
    /** The slot each material of a hub pattern goes into (a hatch's number, or the machine's GUI slot). */
    public static final PatternData<PatternInputGuiSlots> PATTERN_INPUT_GUI_SLOTS = PatternComponents.PATTERN_INPUT_GUI_SLOTS;
    /** The GUI slot each product of a hub pattern is taken out of, for machines that give them only in a GUI. */
    public static final PatternData<PatternOutputGuiSlots> PATTERN_OUTPUT_GUI_SLOTS = PatternComponents.PATTERN_OUTPUT_GUI_SLOTS;
    /** The products of a hub pattern that count as made once the run's materials are all in the machine. */
    public static final PatternData<PatternDispatchDone> PATTERN_DISPATCH_DONE = PatternComponents.PATTERN_DISPATCH_DONE;
    /** What each material of a hub pattern is besides used up: catalyst, catalyst kept for the batch, tool to right-click with. */
    public static final PatternData<PatternInputRoles> PATTERN_INPUT_ROLES = PatternComponents.PATTERN_INPUT_ROLES;

    /** The twin-star order screen (replaces AE2's amount screen on grids with a hub). */
    public static final RegistryObject<MenuType<OrderMenu>> ORDER_MENU = MENUS.register("order",
            () -> MenuTypeBuilder.create(OrderMenu::new, ISubMenuHost.class)
                    .build("order"));
    /** The twin-star plan screen (in place of AE2's confirm screen for orders from the order screen). */
    public static final RegistryObject<MenuType<PlanMenu>> PLAN_MENU = MENUS.register("plan",
            () -> MenuTypeBuilder.create(PlanMenu::new, ISubMenuHost.class)
                    .build("plan"));

    public static final RegistryObject<BigStorageBlock> BIG_STORAGE = BLOCKS.register("big_storage",
            () -> new BigStorageBlock(BlockBehaviour.Properties.of().strength(2.2f, 11f).sound(SoundType.METAL)));
    public static final RegistryObject<BlockItem> BIG_STORAGE_ITEM = ITEMS.register("big_storage",
            () -> new BlockItem(BIG_STORAGE.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<BigStorageBlockEntity>> BIG_STORAGE_BE =
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
        bus.addListener(TwinstarContent::addToCreativeTab);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS)
            event.accept(BIG_STORAGE_ITEM);
    }
}
