package dev.physicalist;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PhysicalistContent {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, PhysicalistLibrary.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, PhysicalistLibrary.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PhysicalistLibrary.MOD_ID);

    public static final DeferredHolder<Item, Item> PHYSICS_WAND = ITEMS.register("physics_wand",
            () -> new PhysicsWandItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> ASSEMBLER = ITEMS.register("assembler",
            () -> new PhysicalistAssemblerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> DELETER = ITEMS.register("deleter",
            () -> new PhysicalistDeleterItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<EntityType<?>, EntityType<PhysicalBlockEntity>> PHYSICAL_BLOCK =
            ENTITIES.register("physical_block", () -> EntityType.Builder
                    .<PhysicalBlockEntity>of(PhysicalBlockEntity::new, MobCategory.MISC)
                    .sized(1, 1).clientTrackingRange(64).updateInterval(1)
                    .build("physicalist_library:physical_block"));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = TABS.register("physicalist",
            () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.physicalist_library"))
                    .icon(() -> new ItemStack(PHYSICS_WAND.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(PHYSICS_WAND.get());
                        output.accept(ASSEMBLER.get());
                        output.accept(DELETER.get());
                    }).build());

    private PhysicalistContent() {}
}
