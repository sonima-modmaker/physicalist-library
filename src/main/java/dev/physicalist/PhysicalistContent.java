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

    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, PhysicalistLibrary.MOD_ID);
    /** A ragdoll's bone snapping (CC0 "Deep Bone Crack/Break SFX" by Zane Little Music, opengameart.org). */
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> BONE_BREAK = SOUNDS.register("bone_break",
            () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(PhysicalistLibrary.MOD_ID, "bone_break")));
    public static final DeferredHolder<Item, Item> PHYSICS_WAND = ITEMS.register("physics_wand",
            () -> new PhysicsWandItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item,Item> CHAIN_TOOL=ITEMS.register("chain_tool",()->new PhysicsChainItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<EntityType<?>,EntityType<PhysicalChainEntity>> PHYSICAL_CHAIN=ENTITIES.register("physical_chain",()->EntityType.Builder.<PhysicalChainEntity>of(PhysicalChainEntity::new,MobCategory.MISC).sized(.2f,.2f).clientTrackingRange(64).updateInterval(1).build("physicalist_library:physical_chain"));
    public static final DeferredHolder<Item, Item> ASSEMBLER = ITEMS.register("assembler",
            () -> new PhysicalistAssemblerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> DELETER = ITEMS.register("deleter",
            () -> new PhysicalistDeleterItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<EntityType<?>, EntityType<PhysicalBlockEntity>> PHYSICAL_BLOCK =
            ENTITIES.register("physical_block", () -> EntityType.Builder
                    .<PhysicalBlockEntity>of(PhysicalBlockEntity::new, MobCategory.MISC)
                    .sized(1, 1).clientTrackingRange(64).updateInterval(1)
                    .build("physicalist_library:physical_block"));
    public static final DeferredHolder<EntityType<?>, EntityType<PlayerRagdollEntity>> PLAYER_RAGDOLL =
            ENTITIES.register("player_ragdoll", () -> EntityType.Builder
                    .<PlayerRagdollEntity>of(PlayerRagdollEntity::new, MobCategory.MISC)
                    .sized(.6F, 1.8F).clientTrackingRange(16).updateInterval(2)
                    .build("physicalist_library:player_ragdoll"));
    public static final DeferredHolder<Item, Item> PHYSICALIST = ITEMS.register("physicalist",
            () -> new dev.physicalist.soft.PhysicalistToolItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<EntityType<?>, EntityType<dev.physicalist.soft.SoftBodyEntity>> SOFT_BODY =
            ENTITIES.register("soft_body", () -> EntityType.Builder
                    .<dev.physicalist.soft.SoftBodyEntity>of(dev.physicalist.soft.SoftBodyEntity::new, MobCategory.MISC)
                    .sized(1, 1).clientTrackingRange(16).updateInterval(1)
                    .build("physicalist_library:soft_body"));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = TABS.register("physicalist",
            () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.physicalist_library"))
                    .icon(() -> new ItemStack(PHYSICS_WAND.get()))
                    .displayItems((parameters, output) -> {
                        // The soft-body tool only when the experimental soft bodies are switched on.
                        if (PhysicalistConfig.softBodies()) output.accept(PHYSICALIST.get());
                        output.accept(PHYSICS_WAND.get());
                        output.accept(ASSEMBLER.get());
                        output.accept(DELETER.get());
                        output.accept(CHAIN_TOOL.get());
                    }).build());

    private PhysicalistContent() {}
}
